# Data Processor V3.6.1

Spring Boot background worker for real-time audit-trail processing. It consumes raw audit events from Kafka, keeps per-session state in Redis, runs deterministic rules plus the V3.6.1 hybrid AI runtime, persists analysis to SQL Server, publishes anomaly alerts to Kafka, and refreshes Redis dashboard snapshots for `api-service`.

The dataprocessor does not call an LLM. It prepares structured evidence payloads for `api-service`, which can generate natural-language explanations later on demand.

## Hybrid AI Runtime

```
Kafka audit event
  -> AuditTrailConsumer
  -> Redis session buffer
  -> FeatureEngineeringService
  -> sequence feature pipeline
  -> tabular anomaly feature pipeline
  -> deterministic rule pipeline
  -> churn profile feature pipeline
  -> forecast context enrichment
  -> model inference engines
  -> RiskFusionServiceV36
  -> AnomalyTypeAttributionServiceV36
  -> LLM evidence payload generation, no LLM call
  -> SQL Server, Redis snapshots, Kafka anomaly alerts
```

XGBoost ranks suspicious events. LightGBM provides the alerting score. Transformer 2-head ONNX provides behavioral sequence surprise. TCN is the low-latency fallback. Rules provide deterministic security guardrails. Risk fusion combines all available signals.

## Artifact Structure

Runtime artifacts live under `src/main/resources/AI`. `AI/config` is the source of truth.

```
AI/
  MANIFEST_v3_6.json
  deployment_manifest_v3_6.json
  config/
    sequence_metadata.json
    categorical_vocabularies.json
    scaler_params.json
    anomaly_score_config.json
    tabular_anomaly_feature_contract.json
    tabular_feature_contract.json
    tabular_feature_scaler.json
    risk_fusion_config.json
    dashboard_payload_contract.json
    llm_explanation_config.json
    churn_profile_feature_schema.json
    churn_runtime_config.json
    forecast_config.json
  models/
    sequence/
      transformer_sequence_engine.onnx
      tcn_sequence_engine.onnx
    tabular_anomaly/
      anomaly_xgboost.json
      anomaly_xgboost.ubj
      anomaly_lightgbm.txt
      anomaly_catboost.cbm
      anomaly_oneclasssvm.json
    churn/
      churn_profile_only_ExtraTrees.json
    forecast/
      macro_forecaster_anomaly_rate_Ridge.json
      macro_forecaster_total_events_XGBoost.json
      macro_forecaster_total_events_XGBoost.ubj
```

Python-native files such as `.pkl`, `.pt`, `.npz`, `.joblib`, scripts, and notebooks are not runtime artifacts and are ignored by Java runtime loading.

## Raw Audit Event Contract

Kafka events should provide dataset-aligned fields including:

```
record_id, insured_id, session_id, timestamp, hour, day_of_week,
is_weekend, is_business_hours, http_method, api_template, api_family,
controller, frontend_action_name, action_value, action_type,
action_subtype, status, page, device, browser, os, ip_country,
environment_id, session_action_seq, time_since_prev_action_ms,
request_data_size_bytes, response_data_size_bytes
```

Legacy DTO fields remain compatibility fallback only when enabled by configuration.

## Sequence Feature Pipeline

`SequenceEventMapper`, `SequencePreprocessingService`, `SequenceWindowService`, `SequenceOnnxInferenceService`, and `SequenceAnomalyScoringService` keep the existing sequence contract.

The current event `E_t` is scored against the previous window `[E_t-10, ..., E_t-1]`. The current event is appended only after scoring.

ONNX inputs:

```
x_cat  int64[1, 10, 15]
x_cont float32[1, 10, 9]
mask   bool[1, 10]
```

Sequence surprise is not a calibrated probability. It is converted to 0..100 risk for fusion while preserving raw surprise scores.

## Tabular Anomaly Feature Pipeline

`TabularAnomalyFeatureService` builds the feature vector in the exact order from `AI/config/tabular_anomaly_feature_contract.json`:

```
cont_mean__[continuous columns]
cont_last__[continuous columns]
cont_std__[continuous columns]
cat_last_norm__[categorical columns]
cat_nunique_norm__[categorical columns]
window_length_ratio
```

It reuses the same encoded categorical IDs and continuous values produced by the sequence preprocessing pipeline. Unknown categorical IDs remain `0`. Continuous missing values default to `0.0` before applying `tabular_feature_scaler.json`.

Diagnostics are written to:

```
ai:tabular:field-coverage:v3_6
```

## Tabular Model Runtime

Available model runtimes are used. Unsupported or failed runtimes are marked unavailable and processing continues with remaining models and rules.

- XGBoost JSON/UBJ: primary anomaly ranking
- LightGBM TXT: primary alerting score
- CatBoost CBM: optional specialized anomaly signal when Java runtime works
- OneClassSVM JSON: custom RBF scorer for novelty/off-hours signal

## Rules Runtime

Rules emit canonical evidence through `RuleRiskResult`, including rule code, severity, contribution, message, and evidence JSON. Supported codes include:

```
OFF_HOURS_ACCESS
SENSITIVE_API_OFF_HOURS
UNUSUAL_COUNTRY
UNUSUAL_DEVICE
COUNTRY_SWITCH
DEVICE_SWITCH
STATUS_CODE_BURST
RAPID_FIRE_EVENTS
API_SCRAPING_PATTERN
LARGE_DOWNLOAD
DATA_EXTRACTION_PATTERN
IMPOSSIBLE_ENDPOINT_TRANSITION
SKIP_LOGIN
SESSION_TIMEOUT
SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT
```

Old rule names are mapped to canonical codes for API compatibility.

## Risk Fusion

`RiskFusionServiceV36` combines available signals:

```
0.30 * xgboostAnomalyScore100
+ 0.25 * lightgbmAlertScore100
+ 0.20 * transformerRiskScore100
+ 0.10 * tcnRiskScore100
+ 0.15 * ruleRiskScore
+ optional businessContextScore
+ optional aggregationBoost
```

Missing model weights are redistributed among available ML models when configured. Rule weight is not removed. If no ML model is available, rules-only fusion is used.

Risk levels:

```
CRITICAL >= 80
HIGH     >= 60
MEDIUM   >= 35
LOW       < 35
```

## Anomaly Type Attribution

`AnomalyTypeAttributionServiceV36` uses model scores, rules, event context, and session context. It can produce:

```
api_scraping
credential_stuffing
data_exfiltration
off_hours_compromise
session_hijacking
behavioral_sequence_anomaly
unknown_suspicious_behavior
```

If evidence is weak, it returns `unknown_suspicious_behavior`.

## Churn Runtime

Churn uses `profile_only ExtraTrees JSON`:

```
AI/config/churn_profile_feature_schema.json
AI/config/churn_runtime_config.json
AI/models/churn/churn_profile_only_ExtraTrees.json
```

The Java runtime manually traverses the ExtraTrees JSON. If churn artifacts are unavailable, churn returns `UNKNOWN` with a warning.

## Forecast Runtime

Forecast uses:

- Ridge JSON for `anomaly_rate`
- XGBoost JSON/UBJ for `total_events`

If configured models are unavailable, fallback order is naive lag 7, rolling mean 7, then zero.

## Persona Skipped

Persona is intentionally disabled/skipped in the current dataprocessor V3.6.1 refactor.

Returned compatibility values:

```
personaCluster = -1
personaLabel = "persona_disabled"
personaSource = "disabled_v3_6_refactor"
personaWarnings = ["persona_skipped_for_now"]
```

## LLM Evidence Payload Only

The dataprocessor prepares evidence payloads for `api-service`; it does not generate final LLM explanations.

Evidence payloads include event metadata, user/session metadata, risk summary, model scores, model contributions, sequence evidence, tabular evidence, rule evidence, anomaly type attribution, churn context, forecast context, runtime warnings, and instructions for `api-service` to avoid inventing evidence.

Redis key:

```
alert:llm-evidence:{eventId}
```

## Dashboard Output Contracts

Dataprocessor writes Redis snapshots:

```
dashboard:security-overview:v3_6
dashboard:churn:v3_6
dashboard:forecast:v3_6
alerts:live:v3_6
alerts:critical:v3_6
alert:investigation:{eventId}
alert:llm-evidence:{eventId}
user:360:{insuredId}
```

Legacy dashboard keys are kept where possible.

## Redis Keys

Compatibility keys:

```
session:buffer:{sessionId}
session:insight:{insuredId}:{sessionId}
risk:{insuredId}
dashboard:{view}
stats:live:{date}
stats:trend:{date}
```

V3.6.1 keys:

```
session:sequence:v3_6:{sessionId}
session:scores:v3_6:{sessionId}
session:risk:v3_6:{insuredId}:{sessionId}
ai:runtime:health:v3_6
ai:sequence:field-coverage:v3_6
ai:tabular:field-coverage:v3_6
ai:model-latency:v3_6
```

## SQL Output

V3.6.1 adds hybrid model, fusion, attribution, churn, forecast, runtime warning, investigation, and evidence payload columns while keeping old compatibility columns.

Compatibility mappings:

- `ensemble_risk_score`: final risk score
- `anomaly_score`: final risk score compatibility value
- `anomaly_probability`: normalized final risk approximation, not raw sequence surprise
- `binary_detector_artifact`: fusion/runtime context
- `type_confidence`: anomaly type confidence
- `persona_cluster`: `-1` while persona is skipped

## Kafka Output

Anomaly alert payloads use `schemaVersion = "v3.6.1"` and include event/session IDs, final risk, risk level, anomaly type, model scores, model contributions, triggered rules, churn context, disabled persona context, artifact names, and an LLM evidence payload reference.

Large evidence payloads are stored in Redis/SQL, not embedded in Kafka.

## Configuration

Core V3.6.1 settings are under `app.ai` in `application.yaml`, including tabular anomaly toggles, sequence strict schema, load shedding, risk fusion weights, disabled persona, churn thresholds, forecast fallback, and LLM evidence-only behavior.

## Tests

Run:

```bash
./mvnw test
./mvnw verify
```

`verify` may require Docker/Testcontainers for SQL Server integration tests.

## Limitations

- The dataprocessor does not call an LLM.
- Persona is disabled for now.
- Sequence surprise is not a calibrated probability.
- XGBoost and LightGBM Java parsing is runtime-validated; failed runtimes degrade gracefully.
- Optional model artifacts do not crash event processing when unavailable.
- Training scripts and Python-native artifacts are not part of Java runtime logic.
