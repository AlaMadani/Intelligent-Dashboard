# Scoring Reference — Data Processor

## 1. Overview

All model scores are fused into a single **finalRiskScore** (0–100) by `RiskFusionServiceV36`.

### Key classes
| Class | File | Role |
|---|---|---|
| `RiskFusionServiceV36` | `inference/RiskFusionServiceV36.java` | Weighted fusion of all ML + rule scores |
| `RiskFusionResult` | `inference/RiskFusionResult.java` | DTO holding final risk and per-model contributions |
| `AiRiskFusionProperties` | `config/AiRiskFusionProperties.java` | Fusion weights and risk-level thresholds |
| `AiRiskScoringProperties` | `config/AiRiskScoringProperties.java` | Sequence → risk mapping (aiScoreScale) |
| `FeatureEngineeringProperties` | `config/FeatureEngineeringProperties.java` | Alert trigger threshold (session-alert-risk-threshold) |
| `RiskProperties` | `config/RiskProperties.java` | User-risk-profile tier thresholds |

---

## 2. Fusion formula

```
finalRisk = clamp(
    xgbContribution +
    lgbmContribution +
    transformerContribution +
    tcnContribution +
    ruleContribution +
    businessContextContribution +
    aggregationBoost,
  0.0, 100.0)
```

Where:

```
contribution(model) = score100(model) × weight(model)
```

### Default weights (AiRiskFusionProperties)
| Model | Weight |
|---|---|
| XGBoost | 0.30 |
| LightGBM | 0.25 |
| Transformer | 0.20 |
| TCN | 0.10 |
| Rules | 0.15 |
| Business Context | 0.00 |

### Renormalization
When a model is unavailable, its weight is redistributed proportionally among available models (if `renormalizeMissingModelWeights=true`).

### Aggregation boost
| Condition | Boost |
|---|---|
| ≥3 rules triggered | +5 |
| `STATUS_CODE_BURST` + `RAPID_FIRE_EVENTS` | +3 |
| ≥10 downloads + `SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT` | +5 |
| Maximum | +10 (clamped) |

---

## 3. Final risk interpretation

### Scale
- **Range**: 0.0 – 100.0 (clamped)
- **Cannot be negative** (clamped to 0)
- **Cannot exceed 100** (clamped to 100)
- **Raw weighted sum**, not a calibrated probability

### Risk tiers (app.ai.risk-fusion)
| Tier | Range | Log label |
|---|---|---|
| **LOW** | 0.0 – 34.999 | `LOW` |
| **MEDIUM** | 35.0 – 59.999 | `MEDIUM` |
| **HIGH** | 60.0 – 79.999 | `HIGH` |
| **CRITICAL** | 80.0 – 100.0 | `CRITICAL` |

### What risk=30 means
- **30 / 100** on the fused scale
- **LOW** tier (below MEDIUM threshold of 35)
- Not a probability (`anomalyProbability` is set as `finalRisk / 100` but that is an approximation, not a true calibrated probability)
- A score of 30 is **not alarm-worthy** — by itself it does not trigger an alert (alert threshold is 60)
- The session is **not marked as anomalous** (anomaly threshold is `finalRisk >= 35`)

### What risk=35.89 means
- **~36 / 100** on the fused scale
- **MEDIUM** tier (at or above 35.0 but below 60.0)
- `isAnomaly()` returns **true** (since 35.89 >= 35.0)
- **Alert published** because `isAnomaly()` triggers the alert decision, even though `finalRisk < 60`
- This is NOT a bug — it is the intended behavior:
  - `isAnomaly()` threshold (35.0) is the **anomaly detection threshold**
  - `sessionAlertRiskThreshold` (60.0) is the **high-risk standalone threshold**
  - Either condition can trigger an alert

### What sequenceRisk=99.59 means
- The selected sequence model (transformer or TCN) assigns a very high anomaly score to the current event, normalized to the 0–100 scale
- This means the event's sequence characteristics are very different from the learned normal behavior pattern
- This is the `aiRiskScore` output of the sequence model, which applies `100 × (1 - exp(-surpriseScore / 5.0))`
- Even with near-100 sequence risk, the final risk can be lower because sequence contributes only 20% (or 10% for TCN) of the total weight in fusion

### Why final risk can be LOW even with sequenceRisk near 100
- Sequence model contributes only 20% (transformer) or 10% (TCN) of the final risk
- If tabular models contribute low scores and rules don't fire, the weighted sum can still be < 35
- Example: `99.59 × 0.20 = 19.92` → if other scores are all ~0, final risk ≈ 19.92 + aggregation boost → LOW

### Where thresholds are defined
```yaml
app:
  ai:
    risk-fusion:
      medium-threshold: 35.0    # finalRisk >= 35 → MEDIUM
      high-threshold: 60.0      # finalRisk >= 60 → HIGH
      critical-threshold: 80.0  # finalRisk >= 80 → CRITICAL
  features:
    session-alert-risk-threshold: 60.0  # standalone risk threshold for alerts
```

### Alert vs Anomaly — clarification
The alert logic has **two independent paths**:

| Concept | Threshold | Config key |
|---|---|---|
| `isAnomaly()` (binary anomaly flag) | `finalRisk >= 35.0` | `app.ai.risk-fusion.medium-threshold` |
| Alert published because `isAnomaly()` | `finalRisk >= 35.0` (implicit) | via `SessionFinalizationOrchestrator.shouldAlert()` |
| Alert published because `finalRisk` alone | `finalRisk >= 60.0` | `app.features.session-alert-risk-threshold` |
| Dashboard "risky" | `finalRisk >= 35.0` (implicit) | `app.ai.risk-fusion.medium-threshold` |

**Key insight**: the `sessionAlertRiskThreshold` of 60.0 is NOT the only alert threshold. The `isAnomaly()` check at 35.0 (MEDIUM boundary) also triggers alerts. This means **any MEDIUM or higher risk session can trigger an alert**, not just HIGH/CRITICAL.

An alert is published if ANY of:
1. `insight.isAnomaly()` is true → `finalRisk >= 35.0`
2. `finalRiskScore >= 60.0`
3. `ensembleRiskScore >= 60.0`

And the session has not already had an alert published (deduplication via Redis `detectedAnomaly` key).

**Rule-triggered alerts**: When a rule like `API_SCRAPING_PATTERN` fires, it raises the `ruleRiskScore` (35.0 for that rule). If the resulting `finalRisk` exceeds 35.0, `isAnomaly()` returns true and the alert publishes. The rule alone does NOT bypass the 35.0 threshold — but in practice, `ruleRiskScore * 0.15 = 5.25` contribution plus other model contributions easily pushes `finalRisk` over 35.0 if any other model is also elevated.

### ALERT_DECISION log
Starting from the fix, every alert eligibility check logs an `ALERT_DECISION` entry explaining the decision:

```text
ALERT_DECISION sessionId=... finalRisk=35.89 riskTier=MEDIUM triggeredRules=[API_SCRAPING_PATTERN]
    sequenceRunBoth=false sequenceSelectedModel=transformer
    sequenceActuallyRanModels=[transformer] transformerContribution=19.92 tcnContribution=0.0
    alertEligible=true alertPublished=true alertReason=RULE_AND_RISK threshold=n/a

ALERT_DECISION sessionId=... finalRisk=61.2 riskTier=HIGH
    sequenceRunBoth=false sequenceSelectedModel=transformer
    sequenceActuallyRanModels=[transformer] transformerContribution=... tcnContribution=0.0
    alertEligible=true alertPublished=true alertReason=FINAL_RISK_THRESHOLD threshold=60.0

ALERT_DECISION sessionId=... finalRisk=20.0 riskTier=LOW triggeredRules=[]
    sequenceRunBoth=false sequenceSelectedModel=transformer
    sequenceActuallyRanModels=[transformer] transformerContribution=... tcnContribution=0.0
    alertEligible=false alertPublished=false alertReason=BELOW_THRESHOLD threshold=60.0
```

Possible `alertReason` values:
| Reason | Meaning |
|---|---|
| `ANOMALY_THRESHOLD` | `isAnomaly()` returned true (finalRisk >= 35.0), no triggered rules |
| `RULE_AND_RISK` | Both `isAnomaly()` true AND triggered rules present |
| `FINAL_RISK_THRESHOLD` | finalRisk >= 60.0 standalone threshold |
| `RULE_TRIGGER` | Rules triggered but `isAnomaly()` false and finalRisk < 60 (edge case) |
| `DUPLICATE_SKIPPED` | Session already has an alert, skipped |
| `BELOW_THRESHOLD` | No condition met, no alert published |

---

## 4. Per-model score details

### 4.1 XGBoost — anomaly ranking
| Property | Value |
|---|---|
| **Class** | `XGBoostTabularAnomalyRuntime` |
| **Method** | `score(TabularAnomalyFeatureVector)` |
| **Input** | 14 scaled numerical features |
| **Algorithm** | JSON tree ensemble (gradient boosted trees) |
| **Raw output** | `predict(vector.scaledValues)` → probability in [0,1] |
| **`score`** | Same as raw (clamped 0–1) |
| **`score100`** | `raw × 100` → [0, 100] |
| **`rawScore`** | Same as `score` |
| **Weight in fusion** | 0.30 |
| **Interpretation** | Higher → more anomalous. A pure probability score (0–1) that represents the model's confidence that the event is anomalous based on tabular features. |
| **Threshold** | Not used directly; contributes to finalRisk |

### 4.2 LightGBM — alerting probability
| Property | Value |
|---|---|
| **Class** | `LightGbmTabularAlertRuntime` |
| **Method** | `score(TabularAnomalyFeatureVector)` |
| **Input** | 14 scaled numerical features |
| **Algorithm** | TXT tree ensemble (gradient boosted trees) |
| **`score`** | `predictProbability()` → probability in [0,1], clamped |
| **`score100`** | `raw × 100` → [0, 100] |
| **`rawScore`** | `predictRaw()` → raw tree sum (unbounded) |
| **Weight in fusion** | 0.25 |
| **Interpretation** | Higher → more likely to be an alert-worthy event. `predictProbability` is the sigmoid-transformed raw margin. |
| **Threshold** | Not used directly; contributes to finalRisk |

### 4.3 CatBoost — anomaly probability (context only, NOT in fusion)
| Property | Value |
|---|---|
| **Class** | `CatBoostTabularAnomalyRuntime` |
| **Method** | `score(TabularAnomalyFeatureVector)` |
| **Input** | 14 float features |
| **Algorithm** | Native CatBoost CBM model |
| **Raw output** | Model raw prediction (can be any real number) |
| **`score`** | If raw ∈ [0,1], use raw; otherwise `sigmoid(raw)` |
| **`score100`** | `score × 100` |
| **Weight in fusion** | **Not used** — CatBoost is not in fusion weights |
| **Interpretation** | Stored for reference/analysis only. Does NOT contribute to `finalRiskScore`. |
| **Threshold** | None |
| **`catboostUsedInFusion`** | `false` |
| **`catboostContribution`** | `0.0` |

### 4.4 OneClassSVM — novelty score (context only, NOT in fusion)
| Property | Value |
|---|---|
| **Class** | `OneClassSvmTabularRuntime` |
| **Method** | `score(TabularAnomalyFeatureVector)` |
| **Input** | 14 scaled numerical features |
| **Algorithm** | RBF-kernel One-Class SVM (decision function) |
| **Raw output** | `anomalyScore = -decision_function()` (positive → anomalous, negative → normal) |
| **`score`** | Same as `rawScore` (unbounded, can be negative) |
| **`score100`** | `100 × sigmoid(anomalyScore)` → [0, 100] |
| **`rawScore`** | `anomalyScore = -decision` |
| **Weight in fusion** | **Not used** — OneClassSVM is not in fusion weights |
| **Interpretation** | `rawScore > 0` → likely anomaly. `rawScore < 0` → likely normal. Inverted from sklearn convention (NoveoCare multiplies by -1 so positive = anomalous). |
| **Threshold** | None |
| **`oneClassSvmUsedInFusion`** | `false` |
| **`oneClassSvmContribution`** | `0.0` |

### 4.5 Transformer — sequence anomaly score
| Property | Value |
|---|---|
| **Class** | `SequenceAnomalyScoringService` |
| **Method** | `score(SequenceInferenceResult, EncodedSequenceEvent)` |
| **Input** | Categorical logits (15 fields) + continuous predictions (9 fields) from ONNX |
| **Raw score** | `categoricalScore + contW × numericError + ctxW × contextError` |
| **`sequenceAnomalyScore`** | Raw score (unbounded, non-negative) |
| **`categoricalScore`** | Sum of NLL contributions for 15 categorical fields |
| **`continuousScore`** | MAE of first 3 continuous predictions |
| **`contextScore`** | MAE of last 6 continuous predictions |
| **`aiRiskScore`** | `100 × (1 - exp(-sequenceScore / aiScoreScale))`, clamped [0, 100] |
| **aiScoreScale** | 5.0 (from `AiRiskScoringProperties`) |
| **Weight in fusion** | 0.20 |
| **Interpretation** | Higher sequenceAnomalyScore → event is more "surprising" relative to learned behavioral patterns. aiRiskScore normalizes to 0–100 scale. A score of 100 means `sequenceScore` is extremely high (→∞). |
| **Threshold** | Not used directly; contributes to finalRisk |

#### Categorical NLL computation
For each categorical field, the model outputs a logits vector. The NLL (negative log-likelihood) is:
```java
nll = logSumExp(logits) - logits[targetIndex]
```
Each NLL is weighted by `catScoreWeights` and `catScoreNorm` from `AnomalyScoreConfig`.

### 4.6 TCN — sequence anomaly score
| Property | Value |
|---|---|
| **Same as Transformer** | Identical output structure via `SequenceAnomalyScoringService` |
| **Model** | `tcn_sequence_engine.onnx` (Temporal Convolutional Network) |
| **Weight in fusion** | 0.10 |
| **Note** | TCN is the "fast model" used under load or when configured. |
| **Selection** | Only runs when: (1) `runBoth=true` and selected model is TRANSFORMER, (2) load shedding selects TCN, or (3) live fast mode switches to TCN |
| **Contribution** | When TCN does NOT run, `tcnContribution = 0.0` and `tcnRiskScore100 = null` |

### 4.7 Rule risk score
| Property | Value |
|---|---|
| **Class** | `RuleRiskScoringService` |
| **Method** | `evaluate(SessionSummary, events, triggeredRules)` |
| **Raw score** | Sum of individual rule contribution scores |
| **`ruleRiskScore`** | `min(100, sum)` |
| **Weight in fusion** | 0.15 |

#### Rule contribution values
| Rule code | Score | Severity |
|---|---|---|
| `OFF_HOURS_ACCESS` | 25.0 | MEDIUM |
| `SENSITIVE_API_OFF_HOURS` | 25.0 | MEDIUM |
| `STATUS_CODE_BURST` | 35.0 | MEDIUM |
| `UNUSUAL_DEVICE` | 35.0 | MEDIUM |
| `DEVICE_SWITCH` | 35.0 | MEDIUM |
| `API_SCRAPING_PATTERN` | 35.0 | MEDIUM |
| `COUNTRY_SWITCH` | 40.0 | HIGH |
| `UNUSUAL_COUNTRY` | 40.0 | HIGH |
| `LARGE_DOWNLOAD` | 40.0 | HIGH |
| `DATA_EXTRACTION_PATTERN` | 40.0 | HIGH |
| `RAPID_FIRE_EVENTS` | 30.0 | MEDIUM |
| `SKIP_LOGIN` | 30.0 | MEDIUM |
| `IMPOSSIBLE_ENDPOINT_TRANSITION` | 30.0 | MEDIUM |
| `SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT` | 30.0 | MEDIUM |
| `SESSION_TIMEOUT` | 20.0 | LOW |

### 4.8 Business context score
| Condition | Score |
|---|---|
| Event outside business hours | +10 |
| `SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT` triggered | +15 |
| Session ended abruptly | +5 |
| Max | 30 (clamped) |

### 4.9 Churn score (context only, NOT in fusion)
| Property | Value |
|---|---|
| **Class** | `ChurnInferenceService` / `ExtraTreesJsonChurnInferenceService` |
| **Method** | `predict()` |
| **Output** | `ChurnPrediction.probability` — probability in [0, 1] |
| **Risk level** | `churnRiskLevel`: `LOW` if < 0.30, `MEDIUM` if < 0.70, `HIGH` if ≥ 0.70 |
| **Used in fusion** | **No** — churn probability is informational only |
| **Used in alert** | Carried in alert payload for context |
| **`churnUsedInSecurityRisk`** | `false` |
| **`churnModel`** | `ExtraTrees` |
| **`churnProbability`** | Probability in [0, 1], or `null` if unavailable |
| **Why `n/d` in frontend?** | If churn inference is disabled, `churnProbability` is `null`. The alert payload includes `churn` sub-object with `probability=null`, `riskLevel=null`. The frontend displays the null as `n/d`. |

### 4.10 Forecast score
| Property | Value |
|---|---|
| **Class** | `ForecastRuntimeService` |
| **Method** | `forecast()` |
| **Output** | `totalEventsForecast`, `anomalyRateForecast` |
| **Used in fusion** | **No** — informational only |
| **Used in live path** | Only if `app.ai.forecast.run-in-listener: true` (default: `false`) |

---

## 5. Sequence model selection (load shedding)

```
selectModel(kafkaLag):
  if lag >= rulesOnlyLagThreshold (20000) → RULES_ONLY
  if lag >= tcnLagThreshold (5000)         → TCN (or configured loadSheddingModel)
  else                                     → TRANSFORMER (or configured primaryModel)
```

### Config flags
```yaml
app:
  ai:
    sequence:
      enabled: true
      transformer-enabled: true
      tcn-enabled: true
      primary-model: transformer
      fast-model: tcn
      load-shedding-model: tcn
      run-both: false
```

### Behavior when both enabled
- If `run-both: true` and `selectedModel == TRANSFORMER` → runs **both** transformer and TCN, selects max risk
- If `run-both: false` (default) → runs the single selected model (transformer or TCN based on load shedding)
- TCN is **not fallback only** — it is explicitly selected when lag ≥ tcnLagThreshold or when `primary-model: tcn`
- Live fast mode can override: `skip-transformer: true` → uses TCN; `skip-sequence: true` → rules only

---

## 6. User risk profile tier

Computed by `StatisticsService.resolveRiskTier()` based on the user's last 30 days of sessions.

| Tier | Condition |
|---|---|
| **HIGH** | `maxRisk >= 80` OR `anomalyRate >= 0.20` OR `avgRisk >= 60` OR high-risk-type seen |
| **MEDIUM** | `anomalyRate >= 0.05` OR `avgRisk >= 35` |
| **LOW** | Otherwise |

### Thresholds (app.risk)
```yaml
app:
  risk:
    medium-threshold: 0.05     # anomaly rate threshold
    high-threshold: 0.20        # anomaly rate threshold
    critical-threshold: 80.0    # max risk threshold
```

---

## 7. Alert evaluation

Alert is published if:

```java
shouldAlert(): insight.isAnomaly()                              // finalRisk >= 35.0
    || finalRiskScore >= sessionAlertRiskThreshold (60.0)       // OR finalRisk >= 60.0
    || ensembleRiskScore >= sessionAlertRiskThreshold (60.0)    // OR ensembleRisk >= 60.0
```

AND the session does NOT already have a `detectedAnomaly` key in Redis (duplicate alert prevention).

**Key clarification**: `isAnomaly()` uses `finalRisk >= 35.0` (MEDIUM tier boundary), which is **lower** than the `sessionAlertRiskThreshold` of 60.0. This means MEDIUM-tier sessions can and do trigger alerts. This is intentional — the 35.0 threshold represents the "suspicious activity" boundary for alerting, while 60.0 represents the "high confidence" standalone alert boundary.

### Existing docs vs code discrepancy

| Claim | Previous docs said | Actual code behavior |
|---|---|---|
| Alert threshold | "alert threshold = 60" | Two conditions: `isAnomaly()` >= 35 OR `finalRisk >= 60`. Both can trigger alerts. |
| Rule-trigger alert | Not documented | Rules contribute to `finalRisk` through the fusion formula. If `finalRisk >= 35` after rule contribution, `isAnomaly()` triggers the alert. |
| MEDIUM risk alert | Not documented | Yes, MEDIUM (35+) can trigger alerts if `isAnomaly()` is true. |

### ALERT_DECISION log

An `ALERT_DECISION` log entry explains each alert eligibility check:

```text
ALERT_DECISION sessionId=... finalRisk=35.89 riskTier=MEDIUM triggeredRules=[API_SCRAPING_PATTERN]
    sequenceRunBoth=false sequenceSelectedModel=transformer
    sequenceActuallyRanModels=[transformer] transformerContribution=19.92 tcnContribution=0.0
    alertEligible=true alertPublished=true alertReason=ANOMALY_THRESHOLD
```

ALERT_DECISION now includes sequence tracking fields:

| Field | Type | Example | Meaning |
|---|---|---|---|
| `sequenceRunBoth` | Boolean | `false` | Whether `runBoth` was enabled |
| `sequenceSelectedModel` | String | `transformer` | Selected sequence model |
| `sequenceActuallyRanModels` | List | `[transformer]` | Models that actually executed |
| `transformerContribution` | Double | `19.92` | Transformer's contribution to finalRisk |
| `tcnContribution` | Double | `0.0` | TCN's contribution (0 if not run) |

| Reason | Meaning |
|---|---|
| `ANOMALY_THRESHOLD` | `isAnomaly()` true, no rules |
| `RULE_AND_RISK` | `isAnomaly()` true AND rules triggered |
| `FINAL_RISK_THRESHOLD` | `finalRisk >= 60.0` |
| `DUPLICATE_SKIPPED` | Already has an alert |
| `BELOW_THRESHOLD` | No condition met |

### RISK_BREAKDOWN log

Every live inference emits a detailed `RISK_BREAKDOWN` log entry per event:

```text
RISK_BREAKDOWN insuredId=... sessionId=... eventId=... stage=LIVE
    xgboostRaw=... xgboostScore100=...
    lightgbmRaw=... lightgbmScore100=...
    catboostRaw=... catboostScore100=...
    oneClassSvmRaw=... oneClassSvmScore100=...
    transformerRaw=... transformerScore100=... tcnRaw=... tcnScore100=...
    sequenceSelectedModel=... sequenceScore100=...
    sequenceRunBoth=... sequenceActuallyRanModels=...
    transformerUsedInFusion=... tcnUsedInFusion=...
    ruleRiskScore=... businessContextScore=... aggregationBoost=...
    churnRaw=... churnScore100=... churnUsedInFusion=false
    forecastRaw=... forecastScore100=... forecastUsedInFusion=false
    xgbContribution=... lgbmContribution=... transformerContribution=...
    tcnContribution=... ruleContribution=... businessContribution=...
    finalRisk=... riskScale=ZERO_TO_ONE_HUNDRED riskTier=... triggeredRules=...
```

### RISK_BREAKDOWN sequence fields

| Field | Type | Example | Meaning |
|---|---|---|---|
| `sequenceRunBoth` | boolean | `false` | Whether `runBoth` config was enabled for this inference |
| `sequenceSelectedModel` | String | `transformer` | The model selected by load shedding (or the max-risk model if runBoth=true) |
| `sequenceActuallyRanModels` | List | `[transformer]` | Which model(s) actually executed inference (not copied/backfilled) |
| `transformerUsedInFusion` | boolean | `true` | Whether transformer score was used in the fusion formula |
| `tcnUsedInFusion` | boolean | `false` | Whether TCN score was used in the fusion formula |

---

## 8. Model score summary table

| Score | Range | 0–1 | 0–100 | In fusion | Weight | Source |
|---|---|---|---|---|---|---|---|
| `xgboostAnomalyScore` | 0–1 | Raw | — | Yes | 0.30 | XGBoost tree ensemble |
| `xgboostAnomalyScore100` | 0–100 | — | Score100 | Yes | 0.30 | XGBoost |
| `lightgbmAlertScore` | 0–1 | Raw | — | Yes | 0.25 | LightGBM tree ensemble |
| `lightgbmAlertScore100` | 0–100 | — | Score100 | Yes | 0.25 | LightGBM |
| `catboostAnomalyScore` | 0–1 | Raw | — | **No** | — | CatBoost CBM (context only) |
| `catboostAnomalyScore100` | 0–100 | — | Score100 | **No** | — | CatBoost |
| `oneClassSvmNoveltyScoreRaw` | unbounded | Raw | — | **No** | — | One-Class SVM (context only) |
| `oneClassSvmNoveltyScore100` | 0–100 | — | Score100 | **No** | — | One-Class SVM |
| `transformerSurpriseScoreRaw` | unbounded | Raw | — | **Indirect** | — | ONNX Transformer |
| `transformerRiskScore100` | 0–100 | — | Score100 | Yes | 0.20 | ONNX Transformer |
| `tcnSurpriseScoreRaw` | unbounded | Raw | — | **Indirect** | — | ONNX TCN |
| `tcnRiskScore100` | 0–100 | — | Score100 | Yes | 0.10 | ONNX TCN |
| `ruleRiskScore` | 0–100 | — | Score100 | Yes | 0.15 | Deterministic rules |
| `businessContextScore` | 0–30 | — | Score100 | Yes | 0.00 | Context heuristics |
| `aggregationBoost` | 0–10 | — | — | Summed | — | Rule combo boost |
| `finalRiskScore` | 0–100 | — | Fused | — | — | Fused result |
| `churnProbability` | 0–1 | Raw | — | **No** | — | ExtraTrees churn (context only) |
| `churnScore100` | 0–100 | — | Score100 | **No** | — | churnProbability × 100 |
| `forecastAnomalyRate` | unbounded | Raw | — | **No** | — | Ridge regression (context only) |
| `forecastTotalEvents` | unbounded | Raw | — | **No** | — | XGBoost (context only) |

### Note on context-only models
CatBoost, OneClassSVM, churn, and forecast scores are stored in `modelScores` and persisted to the DB for reference, but they **do not affect** `finalRiskScore`. They are displayed in the UI as informational fields. If you need them to contribute to risk, a formula change in `RiskFusionServiceV36` would be required.

### Transformer vs TCN contribution behavior
When `runBoth=false` (default), only one sequence model runs:
- **transformer** is selected by default (unless load shedding selects TCN or live fast mode overrides)
- The **non-selected** model's `riskScore100` is `null`
- The **non-selected** model contributes `0.0` to `finalRisk`
- Only the selected model's `aiRiskScore` is used in fusion
- **Critically**: the non-selected model's score is NOT a copy of the selected model's score (fixed in Level E — see bug example below)

When `runBoth=true` (explicitly enabled):
- Both transformer and TCN run simultaneously
- Both `riskScore100` values are populated
- Both contribute to `finalRisk` with their respective weights (20% transformer + 10% TCN)
- The selected model for `selectedScore` is the one with the higher `aiRiskScore`

The `modelScores` map in the alert payload always contains both `transformerRiskScore100` and `tcnRiskScore100` (either the actual score or `null`). Consumers should check which fields are non-null to determine which model(s) ran.

### Bug example (fixed in Level E)

Before the fix, when `runBoth=false` and transformer ran, the TCN score was incorrectly populated with a copy of the transformer score. This caused double-counting in the fusion formula:

| Field | Before fix (bug) | After fix (correct) |
|---|---|---|
| `transformerScore100` | 99.59 | 99.59 |
| `tcnScore100` | **99.59** (copy) | **null** |
| `transformerContribution` | 99.59 × 0.20 = 19.92 | 99.59 × 0.20 = 19.92 |
| `tcnContribution` | 99.59 × 0.10 = **9.96** (wrong) | **0.0** |
| `ruleContribution` | 35.0 × 0.15 = 5.25 | 35.0 × 0.15 = 5.25 |
| `finalRisk` | **35.89 → MEDIUM → alert** | **25.89 → LOW → no alert** |

**Root cause**: `ModelInferenceService.java` lines 289-293 (removed) contained a backfill that copied `selectedScore` into the non-selected model's result when its timing was `>= 0`. Since Java default `long` is `0`, the non-selected model's timing was always `>= 0`, causing the backfill to fire unconditionally.

**After fix**: When only one sequence model runs, the non-selected model's contribution is `0.0` and its score is `null`, so `finalRisk` correctly reflects only the selected model's contribution. A `finalRisk ≈ 25.89` is `LOW` tier and does not trigger an alert (unless the rule alone would cause `isAnomaly()` at `>= 35`).

### riskScale
All risk scores use `riskScale=ZERO_TO_ONE_HUNDRED`. This is included in the alert and session payload to disambiguate from other potential scales (e.g., 0–1 probabilities).
