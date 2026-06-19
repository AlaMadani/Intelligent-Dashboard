# Alert Payload Contract — Data Processor → API Service / Frontend

> **Note**: For SQL snapshot fallback architecture, see [`docs/persistence-strategy.md`](persistence-strategy.md).
> The `dashboard_snapshots` table provides durable SQL fallback for dashboard views when Redis cache misses.

## Overview

The Data Processor publishes anomaly alerts via:
1. **Kafka topic** `anomaly-alerts` — JSON payload (`AnomalyAlert` DTO)
2. **Redis** — `liveAlertsV36Key` / `userAlertsKey` — cached alert payload
3. **SQL** — `anomaly_events` table (via `AnomalyEvent` entity)

The API service reads from Redis/SQL and returns fields to the frontend.

---

## AnomalyAlert DTO — Published Kafka Payload

### Identifiers

| Field | Type | Always present | Notes |
|---|---|---|---|
| `schemaVersion` | String | Yes | `"v3.6.1"` |
| `recordId` | String | Yes | Event ID of triggering event |
| `insuredId` | String | Yes | User/tenant identifier |
| `sessionId` | String | Yes | Session identifier |
| `eventId` | String | Yes | Same as `recordId` |

### Detection fields

| Field | Type | Always present | Notes |
|---|---|---|---|
| `anomalyTier` | String | Yes | `"SESSION_RUNTIME"` or `"SYSTEM"` |
| `anomalyType` | String | Yes | e.g. `"unknown_suspicious_behavior"` |
| `anomalyScore` | Double | Yes | `finalRiskScore` |
| `anomalyProbability` | Double | Yes | `finalRiskScore / 100.0` |
| `typeConfidence` | Double | Maybe | Nullable |
| `anomalyTypeConfidence` | Double | Maybe | Same as `typeConfidence` |
| `ruleType` | String | Maybe | Comma-joined triggered rule codes, or null |
| `anomalyFlag` | Boolean | Yes | `true` if `isAnomaly()` |
| `anomalyTypeSource` | String | Maybe | Source of anomaly type attribution |

### Risk fields

| Field | Type | Always present | Notes |
|---|---|---|---|
| `riskScore` | Double | Yes | `finalRiskScore ?? ensembleRiskScore` |
| `riskLevel` | String | Yes | `"LOW"`, `"MEDIUM"`, `"HIGH"`, `"CRITICAL"` |
| `riskTier` | String | Yes | Alias for `riskLevel` (added for frontend compatibility) |
| `riskScale` | String | Yes | `"ZERO_TO_ONE_HUNDRED"` |
| `finalRiskScore` | Double | Yes | Fused risk score (0–100) |
| `aiRiskScore` | Double | Yes | Max of xgboost100, lightgbm100, sequence100 |
| `ruleRiskScore` | Double | Yes | Sum of rule contribution scores |

### Model scores (map)

`modelScores` is a `Map<String, Object>` containing:

| Key | Type | Nullable | 0–1 vs 0–100 | Notes |
|---|---|---|---|---|
| `xgboostAnomalyScore` | Double | Yes | 0–1 | Raw XGBoost probability |
| `xgboostAnomalyScore100` | Double | Yes | 0–100 | score100 = raw × 100 |
| `lightgbmAlertScore` | Double | Yes | 0–1 | Raw LightGBM probability |
| `lightgbmAlertScore100` | Double | Yes | 0–100 | score100 = raw × 100 |
| `catboostAnomalyScore` | Double | Yes | 0–1 | Context only, not in fusion |
| `catboostAnomalyScore100` | Double | Yes | 0–100 | Context only, not in fusion |
| `oneClassSvmNoveltyScoreRaw` | Double | Yes | unbounded | Context only, not in fusion |
| `oneClassSvmNoveltyScore100` | Double | Yes | 0–100 | Context only, not in fusion |
| `transformerSurpriseScoreRaw` | Double | Yes | unbounded | Null if transformer did not run (when TCN selected or `runBoth=false`) |
| `transformerRiskScore100` | Double | Yes | 0–100 | Null if transformer did not run |
| `tcnSurpriseScoreRaw` | Double | Yes | unbounded | Null if TCN did not run (when transformer selected or `runBoth=false`) |
| `tcnRiskScore100` | Double | Yes | 0–100 | Null if TCN did not run |
| `sequenceRunBoth` | Boolean | Yes | | Whether `runBoth` config was enabled |
| `sequenceActuallyRanModels` | List&lt;String&gt; | Yes | | List of sequence models that actually executed (e.g. `["transformer"]` or `["transformer","tcn"]`) |
| `transformerUsedInFusion` | Boolean | Yes | | Whether transformer score was used in fusion |
| `tcnUsedInFusion` | Boolean | Yes | | Whether TCN score was used in fusion |
| `ruleRiskScore` | Double | Yes | 0–100 | Always present (0.0 if no rules) |

### Model contributions (map)

`modelContributions` is a `Map<String, Object>` containing:

| Key | Type | Notes |
|---|---|---|
| `xgboost` | Double | `xgboostAnomalyScore100 × weight` |
| `lightgbm` | Double | `lightgbmAlertScore100 × weight` |
| `transformer` | Double | `transformerRiskScore100 × weight`, 0.0 if not used |
| `tcn` | Double | `tcnRiskScore100 × weight`, 0.0 if not used |
| `rules` | Double | `ruleRiskScore × 0.15` |
| `businessContext` | Double | Usually 0.0 (weight = 0.00) |
| `aggregationBoost` | Double | 0–10, rule combo boost |

### Triggered rules

`triggeredRules` is a `List<String>` of rule codes (e.g. `["API_SCRAPING_PATTERN"]`).

### Sequence evidence (structured sub-object)

`sequenceEvidence` is a `Map<String, Object>` containing:

| Key | Type | Notes |
|---|---|---|
| `contextAvailable` | Boolean | Whether sequence context was sufficient |
| `selectedModel` | String | `"TRANSFORMER"`, `"TCN"`, or `null` |
| `anomalyScore` | Double | Raw sequence surprise score (unbounded) |
| `categoricalScore` | Double | Categorical NLL contribution |
| `continuousScore` | Double | Continuous MAE contribution |
| `contextScore` | Double | Context MAE contribution |
| `surpriseScoreRaw` | Double | The selected model's raw surprise score |
| `riskScore100` | Double | The selected model's aiRiskScore (0–100) |
| `latencyMs` | Long | Sequence inference latency in ms |
| `windowSize` | Integer | Always `null` (not currently stored) |
| `runBoth` | Boolean | Whether `runBoth` config was enabled |
| `actuallyRanModels` | List&lt;String&gt; | Models that actually executed (e.g. `["transformer"]`) |
| `transformerUsedInFusion` | Boolean | Whether transformer score contributed to finalRisk |
| `tcnUsedInFusion` | Boolean | Whether TCN score contributed to finalRisk |
| `available` | Boolean | Whether sequence model produced a score |

### Tabular evidence (structured sub-object)

`tabularEvidence` is a `Map<String, Object>` containing:

| Key | Type | Notes |
|---|---|---|
| `availableModels` | List<String> | Models that ran successfully |
| `unavailableModels` | List<String> | Models that failed to run |
| `featureWarnings` | List<String> | Feature-related warnings |
| `available` | Boolean | Whether any tabular model produced a score |

### Event metadata (structured sub-object)

`eventMetadata` is a `Map<String, Object>` containing:

| Key | Type | Notes |
|---|---|---|
| `eventId` | String | Triggering event ID |
| `action` | String | Event action (e.g. `"login"`, `"search"`) |
| `apiTemplate` | String | API template path |
| `apiFamily` | String | API family |
| `controller` | String | Controller name |
| `page` | String | Page name |
| `country` | String | IP country or country code |
| `device` | String | Device type |
| `browser` | String | Browser name |
| `os` | String | Operating system |
| `httpMethod` | String | HTTP method (e.g. `"GET"`, `"POST"`) |
| `status` | String | HTTP status code |
| `route` | String | Route path |
| `eventTime` | Instant | Event timestamp |
| `available` | Boolean | Always `true` when alert has a triggering event |

### Flat event metadata fields (legacy — use `eventMetadata` sub-object for new consumers)

| Field | Source | Notes |
|---|---|---|
| `eventAction` | `lastEvent.getAction()` | May be null |
| `apiTemplate` | `lastEvent.getApiTemplate()` | May be null |
| `apiFamily` | `lastEvent.getApiFamily()` | May be null |
| `controller` | `lastEvent.getController()` | May be null |
| `page` | `lastEvent.getPage()` | May be null |
| `country` | `lastEvent.getIpCountry()` ?? `lastEvent.getCountryCode()` | May be null |
| `device` | `lastEvent.getDevice()` | May be null |
| `browser` | `lastEvent.getBrowser()` | May be null |
| `os` | `lastEvent.getOs()` | May be null |
| `httpMethod` | `lastEvent.getHttpMethod()` | May be null |
| `status` | `lastEvent.getStatus()` | May be null |

### Churn context

`churn` is a `Map<String, Object>` containing:

| Key | Type | Notes |
|---|---|---|
| `probability` | Double | Churn probability [0, 1], or null if unavailable |
| `riskLevel` | String | `"LOW"`, `"MEDIUM"`, `"HIGH"`, or null |
| `modelName` | String | `"ExtraTrees"` or null |
| `artifact` | String | Model artifact name or null |

Also at top level:
| Field | Type | Notes |
|---|---|---|
| `churnProbability` | Double | Same as `churn.probability` |
| `churnRiskLevel` | String | Same as `churn.riskLevel` (in cached live alert) |

### Forecast context

Forecast is NOT a top-level field in `AnomalyAlert`. It is embedded in:
- `insight.forecastContext` — a `Map<String, Object>` stored in Redis
- `SessionAnalysis.forecastContextJson` — JSON in SQL

### Persona context

`persona` is a `Map<String, Object>` containing:

| Key | Type | Notes |
|---|---|---|
| `enabled` | Boolean | Always `false` in V3.6.1 |
| `label` | String | Null |
| `source` | String | Null |
| `confidence` | Double | Null |
| `warnings` | List<String> | Runtime warnings |

### LLM evidence

| Field | Type | Notes |
|---|---|---|
| `llmEvidencePayloadAvailable` | Boolean | True if LLM evidence payload was generated |
| `llmEvidencePayloadRedisKey` | String | Redis key to fetch the full evidence payload |

### LLM evidence payload — `alertLlmEvidenceKey` (Redis) / `session_analysis.llm_explanation_evidence_payload_json` (SQL)

The full evidence payload is a `Map<String, Object>` with these top-level fields:

| Key | Type | Notes |
|---|---|---|
| `schemaVersion` | String | `"v3.6.1"` |
| `evidenceVersion` | String | `"1.0"` — incremented when payload structure changes |
| `evidenceHash` | String | SHA-256 hex of canonical JSON payload (excluding `evidenceHash` itself) — stable for same evidence, changes when model/rule evidence changes |
| `eventId` | String | Triggering event ID |
| `recordId` | String | Same as `eventId` |
| `insuredId` | String | User/tenant identifier |
| `sessionId` | String | Session identifier |
| `timestamp` | Instant | Event timestamp |
| `evidenceCreatedAt` | String | ISO-8601 instant when this payload was built |
| `eventMetadata` | Map | Structured event metadata (see Event metadata table) |
| `userMetadata` | Map | User/persona metadata |
| `sessionMetadata` | Map | Session summary metadata |
| `risk` | Map | `{finalRiskScore, riskLevel, fallbackMode}` |
| `modelScores` | Map | Same as `AnomalyAlert.modelScores` |
| `modelContributions` | Map | Same as `AnomalyAlert.modelContributions` |
| `triggeredRules` | List | Rule contribution objects or list of strings |
| `sequenceEvidence` | Map | Structured sequence evidence (see below) |
| `tabularEvidence` | Map | Structured tabular evidence (see below) |
| `ruleEvidence` | Map | Raw rule evidence |
| `anomalyTypeAttribution` | Map | `{anomalyType, confidence, source, evidence}` |
| `churnContext` | Map | `{churnProbability, churnRiskLevel}` |
| `forecastContext` | Map | Forecast prediction context |
| `runtimeWarnings` | List | Runtime warnings |
| `evidenceReferences` | Map | Links to feature contract and runtime health keys |
| `llmInstruction` | Map | `{consumer, task, doNotInventEvidence}` |
| `llmExplanationInDataprocessor` | Boolean | Always `false` — LLM explanation generation lives in api-service |
| `evidenceSummary` | String | Compact summary like `"risk=42.5; level=HIGH; type=unknown_suspicious_behavior; fallback=UNKNOWN"` |

#### Sequence evidence sub-object (`sequenceEvidence`)

| Key | Type | Notes |
|---|---|---|
| `selectedSequenceModel` | String | `"TRANSFORMER"`, `"TCN"`, or null |
| `sequenceModelArtifact` | String | Model artifact name |
| `contextAvailable` | Boolean | Whether sequence context was sufficient |
| `windowSize` | Integer | Always `10` |
| `sequenceRunBoth` | Boolean | Whether `runBoth` config was enabled |
| `sequenceActuallyRanModels` | List&lt;String&gt; | Models that actually executed (e.g. `["transformer"]`) |
| `transformerUsedInFusion` | Boolean | Whether transformer score contributed to finalRisk |
| `tcnUsedInFusion` | Boolean | Whether TCN score contributed to finalRisk |
| `transformerSurpriseScoreRaw` | Double | Raw transformer surprise score, null if transformer did not run |
| `transformerRiskScore100` | Double | Transformer risk score (0–100), null if transformer did not run |
| `tcnSurpriseScoreRaw` | Double | Raw TCN surprise score, null if TCN did not run |
| `tcnRiskScore100` | Double | TCN risk score (0–100), null if TCN did not run |
| `topSurpriseFields` | List&lt;String&gt; | Top contributing field names from sequence model |

### Evidence hash stability

The `evidenceHash` is a SHA-256 hex digest computed over the canonical JSON representation of the entire payload (excluding the `evidenceHash` key itself). This ensures:

| Scenario | Hash behavior |
|---|---|
| Same model/rule evidence, same payload | **Stable** — identical hash across Redis/SQL writes |
| Risk score changes (e.g. 42.5 → 55.0) | **Changes** — JSON serialization differs |
| Different triggered rules | **Changes** — JSON serialization differs |
| TCN null vs TCN ran | **Changes** — JSON serialization differs |

The hash is computed by Jackson `ObjectMapper.writeValueAsString()` and is deterministic across JVM restarts for identical input.

### SQL fallback — `session_analysis.llm_explanation_evidence_payload_json`

The full evidence payload is persisted to `session_analysis.llm_explanation_evidence_payload_json` (NVARCHAR(MAX)) during `SessionFinalizationOrchestrator.persistSessionAnalysis()`. The api-service can retrieve it via:

```
SELECT llm_explanation_evidence_payload_json
FROM session_analysis
WHERE insuredId = :insuredId AND sessionId = :sessionId
ORDER BY createdAt DESC
LIMIT 1
```

This column is written on the session finalization thread (not the hot Kafka event thread), so it may lag by the session TTL. For real-time access, use the Redis `alertLlmEvidenceKey`.<｜end▁of▁thinking｜>Now let me update the tests. I need to add tests to `LlmEvidencePayloadServiceTest.java`:

<｜｜DSML｜｜tool_calls>
<｜｜DSML｜｜invoke name="todowrite">
<｜｜DSML｜｜parameter name="todos" string="false">[{"priority":"high","content":"Add evidenceHash/version/createdAt to LlmEvidencePayloadService","status":"completed"},{"priority":"high","content":"Add sequence tracking fields to sequenceEvidence sub-object","status":"completed"},{"priority":"high","content":"Add logging in ModelInferenceService for evidence writes","status":"completed"},{"priority":"high","content":"Add LLM_EVIDENCE_PAYLOAD_WRITE logs in SessionFinalizationOrchestrator","status":"completed"},{"priority":"medium","content":"Update docs/alert-payload-contract.md with evidence hash/version/sequence","status":"completed"},{"priority":"high","content":"Add tests for evidence hash, sequence fields, TCN null preservation","status":"in_progress"},{"priority":"high","content":"Verify build and run tests","status":"pending"}]

### Other fields

| Field | Type | Notes |
|---|---|---|
| `personaCluster` | Integer | Always null (persona disabled) |
| `personaLabel` | String | Always null |
| `pathDeviation` | Boolean | Whether path deviation detected |
| `transitionProbability` | Double | Path transition probability |
| `transitionFromAction` | String | From action for path deviation |
| `transitionToAction` | String | To action for path deviation |
| `modelArtifact` | String | Binary detector artifact name |
| `artifactNames` | Map<String,String> | Model artifact names |
| `nextActions` | List | Next action predictions (empty if not available) |
| `eventTime` | Instant | Triggering event timestamp |
| `detectedAt` | Instant | Alert detection timestamp |

---

## Redis Cached Payload

The `cacheV36Alert` method writes to `liveAlertsV36Key` and `userAlertsKey`. The cached map includes:

| Key | Source | Notes |
|---|---|---|
| `schemaVersion` | `"v3.6.1"` | |
| `eventId` | `alert.eventId` | |
| `recordId` | `alert.recordId` | |
| `insuredId` | `alert.insuredId` | |
| `sessionId` | `alert.sessionId` | |
| `timestamp` | `alert.eventTime` | |
| `eventAction` | `alert.eventAction` | |
| `apiTemplate` | `alert.apiTemplate` | |
| `apiFamily` | `alert.apiFamily` | |
| `controller` | `alert.controller` | |
| `page` | `alert.page` | |
| `country` | `alert.country` | |
| `device` | `alert.device` | |
| `browser` | `alert.browser` | |
| `os` | `alert.os` | |
| `httpMethod` | `alert.httpMethod` | |
| `status` | `alert.status` | |
| `riskLevel` | `alert.riskLevel` | |
| `riskTier` | `alert.riskTier` | Added for frontend compatibility |
| `riskScale` | `alert.riskScale` | `"ZERO_TO_ONE_HUNDRED"` |
| `finalRiskScore` | `alert.finalRiskScore` | |
| `xgboostAnomalyScore` | `insight.xgboostAnomalyScore` | Raw 0–1 |
| `xgboostAnomalyScore100` | `insight.xgboostAnomalyScore100` | 0–100 |
| `lightgbmAlertScore` | `insight.lightgbmAlertScore` | Raw 0–1 |
| `lightgbmAlertScore100` | `insight.lightgbmAlertScore100` | 0–100 |
| `transformerRiskScore100` | `insight.transformerRiskScore100` | May be null |
| `tcnRiskScore100` | `insight.tcnRiskScore100` | May be null |
| `ruleRiskScore` | `insight.ruleRiskScore` | |
| `modelScores` | `insight.modelScores` | Full map |
| `modelContributions` | `insight.modelContributions` | Full map |
| `triggeredRuleCodes` | `alert.triggeredRules` | |
| `anomalyType` | `alert.anomalyType` | |
| `anomalyTypeConfidence` | `alert.anomalyTypeConfidence` | |
| `churnProbability` | `insight.churnProbability` | |
| `churnRiskLevel` | `insight.churnRiskLevel` | |
| `llmEvidencePayloadAvailable` | `alert.llmEvidencePayloadAvailable` | |
| `llmEvidencePayloadRedisKey` | `alert.llmEvidencePayloadRedisKey` | |
| `alertStatus` | `"OPEN"` | Always `"OPEN"` |
| `createdAt` | `alert.detectedAt` | |
| `eventMetadata` | `alert.eventMetadata` | Structured event metadata |
| `sequenceEvidence` | `alert.sequenceEvidence` | Structured sequence evidence |
| `tabularEvidence` | `alert.tabularEvidence` | Structured tabular evidence |

---

## SQL Fallback — `dashboard_snapshots` Table

The `dashboard_snapshots` table stores precomputed dashboard view payloads for durable fallback.
See [`docs/persistence-strategy.md`](persistence-strategy.md) for full schema and behavior.

Key points:
- Redis is primary; SQL `dashboard_snapshots` is fallback
- Written during dashboard refresh scheduler (not on Kafka hot path)
- Upsert by `view_name` + `snapshot_key`
- SHA-256 payload hash skips writes when unchanged

## SQL Schema — `anomaly_events` Table

The `AnomalyEvent` JPA entity maps to `anomaly_events` table. Key columns:

| Column | Type | Source | Notes |
|---|---|---|---|
| `risk_level` | NVARCHAR(32) | `alert.riskLevel` | LOW/MEDIUM/HIGH/CRITICAL |
| `final_risk_score` | DOUBLE | `alert.finalRiskScore` | |
| `xgboost_anomaly_score` | DOUBLE | From `modelScores.xgboostAnomalyScore` | |
| `xgboost_anomaly_score_100` | DOUBLE | From `modelScores.xgboostAnomalyScore100` | |
| `lightgbm_alert_score` | DOUBLE | From `modelScores.lightgbmAlertScore` | |
| `lightgbm_alert_score_100` | DOUBLE | From `modelScores.lightgbmAlertScore100` | |
| `transformer_risk_score_100` | DOUBLE | From `modelScores.transformerRiskScore100` | May be null |
| `tcn_risk_score_100` | DOUBLE | From `modelScores.tcnRiskScore100` | May be null |
| `model_scores_json` | NVARCHAR(MAX) | JSON of `alert.modelScores` | |
| `model_contributions_json` | NVARCHAR(MAX) | JSON of `alert.modelContributions` | |
| `triggered_rules_json` | NVARCHAR(MAX) | JSON of `alert.triggeredRules` | |

---

## API Service / Frontend Contract Notes

### Known field naming mismatches
| Data Processor emits | Frontend appears to expect | Status |
|---|---|---|
| `riskLevel` | `riskLevel` or `riskTier` | **Compatible**: both `riskLevel` and `riskTier` are now emitted |
| `eventAction` (flat) | `eventAction` (in event metadata) | **Compatible**: both flat and structured `eventMetadata` are now emitted |
| `modelScores.transformerRiskScore100` | `transformerRiskScore100` | **Compatible**: flat field in cached alert + in modelScores map |
| `modelScores.tcnRiskScore100` | `tcnRiskScore100` | **Compatible**: flat field in cached alert + in modelScores map |
| `sequenceEvidence` | `Sequence Evidence` section | **New**: structured sub-object added |
| `tabularEvidence` | `Tabular Evidence` section | **New**: structured sub-object added |

### Required frontend follow-up
The frontend and/or API service should:
1. Read `riskTier` or `riskLevel` (both now available, same value)
2. Read `eventMetadata` field for structured event info
3. Read `sequenceEvidence` for sequence details (contextAvailable, selectedModel, scores)
4. Read `tabularEvidence` for tabular model availability
5. Read `riskScale` to disambiguate 0–1 vs 0–100 scales

### Null field handling
When a model did not run (e.g., TCN when transformer was selected with `runBoth=false`):
- `tcnRiskScore100` is `null`
- `tcnSurpriseScoreRaw` is `null`
- `tcnContribution` is `0.0`
- `tcnUsedInFusion` is `false`
- The sequence score in `tcnRiskScore100` is NOT a copy of the transformer score
- Verify which models ran via `sequenceActuallyRanModels` field (e.g. `["transformer"]`)

When a model did run (e.g., Transformer):
- `transformerRiskScore100` has a numeric value
- `transformerUsedInFusion` is `true`
- `transformerContribution` is `score × weight`

Do NOT display a null score as a valid 0. Display it as "N/A" or "not run".
