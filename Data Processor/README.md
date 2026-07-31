# Data Processor — NoveoCare ML Analytics Engine

An event-driven, ML-powered background worker that ingests insured-user audit trails in real time, reconstructs behavioral sessions, applies a multi-tier anomaly detection ensemble (heuristic rules, tree-based models, sequence models), fuses all signals into a risk score, and publishes alerts and analytics to Redis, SQL Server, and Kafka.

**Spring Boot 4.0.3** | **Java 25** | **Non-web worker** (`spring.main.web-application-type: none`)

---

## Table of Contents

1. [Architecture](#architecture)
2. [Event Processing Pipeline](#event-processing-pipeline)
3. [Package Map](#package-map)
4. [ML Models & Detection Strategy](#ml-models--detection-strategy)
5. [Risk Fusion Formula](#risk-fusion-formula)
6. [Heuristic Rules](#heuristic-rules)
7. [Persistence Strategy](#persistence-strategy)
8. [Redis Key Layout](#redis-key-layout)
9. [Database Schema](#database-schema)
10. [Configuration Reference](#configuration-reference)
11. [Local Development](#local-development)
12. [Environment Variables](#environment-variables)

---

## Architecture

```
Cosmos DB
   │
   ▼
Kafka Connect (Cosmos DB source connector)
   │
   ▼
Kafka topic ──► Data Processor ──► Redis (live cache)
  audit-trail      (worker)          │
                     │               ├── Session buffers
                     │               ├── Insights & risk
                     │               ├── Live stats
                     │               └── Dashboard snapshots
                     │
                     ├──► SQL Server (durable storage)
                     │      ├── session_analysis
                     │      ├── anomaly_events
                     │      ├── user_risk_profile
                     │      ├── next_action_prediction
                     │      └── dashboard_snapshots
                     │
                     └──► Kafka topic ──► api-service (read API)
                           anomaly-alerts
```

### Technology Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 25, Spring Boot 4.0.3 |
| Event bus | Apache Kafka 7.7.7 (KRaft mode) |
| Cache / live state | Redis 8.2 |
| Durable storage | SQL Server |
| ML inference | ONNX Runtime (XGBoost, LightGBM, CatBoost, Isolation Forest, Transformer, TCN) |
| Tree-based inference | Custom JSON/TXT tree-walking predictors (XGBoostJsonPredictor, LightGbmTxtPredictor) |
| Feature engineering | In-process analysis (time deltas, change detection, KO streaks, download bursts, ping-pong loops) |
| Containerization | Jib Maven plugin (no Dockerfiles) |
| CI/CD | GitLab CE + GitLab Runner |

---

## Event Processing Pipeline

Each incoming Kafka event goes through this pipeline inside `AuditTrailConsumer.consume()`:

```
Kafka record
  │
  ├─ 1. Parse JSON → AuditTrailEvent
  ├─ 2. Idempotency check (Redis marker)
  ├─ 3. Session state management (SessionFinalizationService)
  ├─ 4. Record statistics (StatisticsService)
  ├─ 5. Append to Redis session buffer (RedisSessionBufferService)
  ├─ 6. Fetch & sort session event history
  ├─ 7. Feature engineering / enrichment (FeatureEngineeringService)
  │      ├─ Time deltas (inter-action gaps)
  │      ├─ Change detection (IP, device, country)
  │      ├─ KO streak tracking
  │      ├─ Download burst detection (sliding 2min window)
  │      └─ Ping-pong loop detection
  ├─ 8. Build/update running session summary
  ├─ 9. Evaluate session-level rules (SessionRuleEvaluator)
  ├─10. Model inference (ModelInferenceService)
  │      ├─ Tabular models: XGBoost, LightGBM, CatBoost, OneClassSVM
  │      ├─ Sequence models: Transformer or TCN (load-shedding selected)
  │      ├─ Churn prediction (ExtraTrees)
  │      └─ Forecast evaluation
  ├─11. Session finalization OR alert publication
  │      ├─ Explicit session end → persist full SessionAnalysis to SQL
  │      └─ Threshold exceeded → publish AnomalyAlert to Kafka + Redis
  ├─12. Cache session insight (DashboardSnapshotService)
  └─13. Acknowledge Kafka offset
```

### Load Shedding

When Kafka consumer lag exceeds thresholds, the pipeline degrades gracefully:

| Lag | Behavior |
|-----|----------|
| ≥ 5,000 | Use TCN instead of Transformer for sequence |
| ≥ 10,000 | Skip all ML models, use heuristics only (`inferLightweight()`) |
| ≥ 20,000 | Rules-only mode (no sequence model at all) |

The `liveFastMode` budget (default 500ms) also caps per-event inference time by skipping slow models.

---

## Package Map

```
com.noveocare.dataprocessor/
│
├── DataProcessorApplication.java              — Spring Boot entry point + startup diagnostics
│
├── ai/                                        — ML inference internals
│   ├── FeatureEngineeringService.java         — Per-event enrichment + session aggregation
│   ├── TextNormalization.java                 — String cleaning for categorical encoding
│   │
│   ├── artifact/                              — AI resource loading & validation
│   │   ├── RuntimeArtifactService.java        — Loads all ONNX models + JSON configs at startup
│   │   ├── RuntimeArtifactManifest.java       — Manifest metadata (model paths, versions)
│   │   ├── RuntimeArtifactHealth.java         — Artifact health summary
│   │   ├── AiResourceValidator.java           — Validates AI resource integrity
│   │   ├── AnomalyScoreConfig.java            — Sequence scoring weights & normalization
│   │   ├── CategoricalVocabularies.java       — Categorical field vocab mappings
│   │   ├── ChurnFeatureSchema.java            — Churn feature column definitions
│   │   ├── ForecastConfig.java                — Forecast model metadata
│   │   ├── PersonaRuntimeKmeansConfig.java    — K-Means cluster definitions
│   │   ├── ScalerParams.java                  — StandardScaler parameters (mean, std)
│   │   └── SequenceMetadata.java              — Sequence model field metadata
│   │
│   ├── churn/                                 — Churn/dropoff prediction
│   │   ├── ChurnInferenceService.java         — Churn inference orchestrator
│   │   ├── ExtraTreesJsonChurnInferenceService.java — JSON tree-walking churn predictor
│   │   ├── ChurnFeatureAssembler.java         — Builds churn feature vectors
│   │   └── ChurnPrediction.java               — Churn result DTO
│   │
│   ├── explanation/                           — LLM evidence payload generation
│   │   └── LlmEvidencePayloadService.java     — Builds structured evidence for Gemini
│   │
│   ├── forecast/                              — Volumetric forecasting
│   │   ├── ForecastRuntimeService.java        — Forecast inference orchestrator
│   │   └── ForecastPrediction.java            — Forecast result DTO
│   │
│   ├── persona/                               — Persona clustering (disabled in v3.6.1)
│   │   ├── PersonaRuntimeService.java         — K-Means persona assignment
│   │   └── PersonaAssignment.java             — Persona cluster result
│   │
│   ├── sequence/                              — Sequence anomaly detection
│   │   ├── SequenceAnomalyScoringService.java — Computes surprise scores from ONNX output
│   │   ├── SequenceOnnxInferenceService.java  — Runs ONNX session for sequence models
│   │   ├── SequencePreprocessingService.java  — Encodes events into model input tensors
│   │   ├── SequenceWindowService.java         — Manages sliding windows of events
│   │   ├── SequenceWindow.java                — Window of sequence events
│   │   ├── SequenceWindowState.java           — Window state for persistence
│   │   ├── SequenceEventMapper.java           — Maps event fields to model inputs
│   │   ├── SequenceEventValues.java           — Categorical + continuous value containers
│   │   ├── SequenceFieldContribution.java     — Per-field surprise contribution
│   │   ├── SequenceFieldCoverageMonitor.java  — Tracks which fields are populated
│   │   ├── SequenceInferenceResult.java       — Raw ONNX output wrapper
│   │   ├── SequenceModelKind.java             — Enum: TRANSFORMER / TCN
│   │   ├── SequenceScoreResult.java           — Scored sequence output
│   │   └── SequenceValueNormalizer.java       — Continuous value z-score normalization
│   │
│   ├── tabular/                               — Tabular anomaly detection
│   │   ├── TabularAnomalyInferenceService.java — Orchestrates all tabular models
│   │   ├── TabularAnomalyFeatureService.java  — Feature extraction for tabular models
│   │   ├── TabularAnomalyFeatureVector.java   — Feature vector container
│   │   ├── TabularAnomalyFeatureContract.java — Feature contract definitions
│   │   ├── TabularAnomalyModelRuntime.java    — Base class for ONNX model runtimes
│   │   ├── TabularAnomalyResult.java          — Aggregated tabular result
│   │   ├── TabularFeatureScaler.java          — StandardScaler for tabular features
│   │   ├── TabularFieldCoverageMonitor.java   — Tracks feature-level coverage
│   │   ├── TabularModelScore.java             — Individual model score container
│   │   ├── XGBoostTabularAnomalyRuntime.java  — XGBoost JSON tree-walker runtime
│   │   ├── LightGbmTabularAlertRuntime.java   — LightGBM TXT tree-walker runtime
│   │   ├── CatBoostTabularAnomalyRuntime.java — CatBoost native runtime
│   │   └── OneClassSvmTabularRuntime.java     — OneClassSVM ONNX runtime
│   │
│   └── tree/                                  — Custom tree-walking predictors
│       ├── XGBoostJsonPredictor.java          — XGBoost JSON-format tree interpreter
│       └── LightGbmTxtPredictor.java          — LightGBM TXT-format tree interpreter
│
├── config/                                    — Configuration properties
│   ├── CacheKeys.java                         — Centralized Redis key naming (shared contract)
│   ├── KafkaConfig.java                       — Kafka listener container factory + partition validation
│   ├── KafkaTopicProperties.java              — Topic name configuration
│   ├── KafkaConsumerProperties.java           — Consumer concurrency & settings
│   ├── JacksonConfig.java                     — ObjectMapper configuration
│   ├── RedisCacheProperties.java              — Redis TTL configuration
│   ├── RedisPubSubProperties.java             — Redis PubSub channel configuration
│   ├── InferenceConfigProperties.java         — Inference timeouts, circuit breaker, live fast mode
│   ├── FeatureEngineeringProperties.java      — Feature engineering constants
│   ├── PerformanceProperties.java             — Dashboard refresh intervals, evidence thresholds
│   ├── SessionFinalizationProperties.java     — Session timeout, flush, grace period config
│   ├── SessionFirstEvent.java                 — First event detection config
│   ├── AiResourceProperties.java              — AI resource path configuration
│   ├── AiSequenceProperties.java              — Sequence model toggles & selection
│   ├── AiTabularAnomalyProperties.java        — Tabular model toggles
│   ├── AiRiskFusionProperties.java            — Fusion weights & risk thresholds
│   ├── AiRiskScoringProperties.java           — Score scaling parameters
│   ├── AiChurnProperties.java                 — Churn thresholds & model selection
│   ├── AiForecastProperties.java              — Forecast refresh & model config
│   ├── AiDiagnosticsProperties.java           — Diagnostics/tracing toggles
│   ├── AiLoadSheddingProperties.java          — Lag-based degradation thresholds
│   ├── AiPersonaProperties.java               — Persona (disabled)
│   ├── AiLiveSessionProperties.java           — Session event limits & window size
│   ├── AiLlmExplanationProperties.java        — LLM evidence payload config
│   ├── LiveStatsProperties.java               — Live stats time window config
│   ├── RiskProperties.java                    — User risk profile thresholds
│   ├── RuleProperties.java                    — Rule definitions & thresholds
│   ├── NextEventPredictionProperties.java     — Next-event prediction config
│   └── NextActionPredictionProperties.java    — Next-action prediction (disabled)
│
├── dto/                                       — Data transfer objects
│   ├── AuditTrailEvent.java                   — Inbound Kafka event DTO
│   ├── AnomalyAlert.java                      — Outbound Kafka alert DTO
│   ├── AnomalyTypeResult.java                 — Anomaly type classification result
│   ├── V36LiveAlertSummary.java               — Cached live alert summary
│   ├── SessionSummary.java                    — Aggregated session features
│   ├── SessionState.java                      — Current session processing state
│   ├── SessionRunningSummary.java             — Incremental session summary
│   ├── SessionInsight.java                    — Full inference output for a session
│   ├── RunningSummaryUpdateResult.java        — Result of running summary update
│   ├── PathDeviationResult.java               — Markov path deviation result
│   ├── NextEventPredictionResult.java         — Next-event prediction output
│   ├── NextEventPredictionDeviation.java      — Deviation from predicted next event
│   ├── NextEventPredictionHeadScore.java      — Per-head prediction score
│   ├── NextActionScore.java                   — Next-action probability
│   ├── FeatureContribution.java               — Per-feature contribution to score
│   └── AuditTrailEvent.java                   — Inbound Kafka event DTO
│
├── entity/                                    — JPA entities
│   ├── AnomalyEvent.java                      — Durable anomaly alert record
│   ├── SessionAnalysis.java                   — Complete session analysis (64+ columns)
│   ├── UserRiskProfile.java                   — Rolling risk assessment per user
│   ├── NextEventPrediction.java               — Next-event prediction record
│   ├── NextActionPrediction.java              — Next-action prediction (deprecated)
│   └── DashboardSnapshot.java                 — Dashboard view cache (SQL fallback)
│
├── inference/                                 — Risk fusion & detection services
│   ├── ModelInferenceService.java             — Orchestrates all ML models per event
│   ├── RiskFusionServiceV36.java              — Weighted fusion of all scores → finalRisk
│   ├── RiskFusionResult.java                  — Fusion output DTO
│   ├── RuleRiskScoringService.java            — Evaluates deterministic rules
│   ├── RuleRiskResult.java                    — Rule evaluation result
│   ├── RuleContribution.java                  — Individual rule contribution
│   ├── AnomalyTypeAttributionServiceV36.java  — Determines anomaly type from all signals
│   ├── AnomalyTypeAttributionResult.java      — Attribution result DTO
│   ├── ModelContribution.java                 — Per-model contribution to final risk
│   ├── ModelHealthService.java                — Tracks model availability & latency
│   ├── InferenceExecutorManager.java          — Async/pipeline inference execution
│   ├── InferenceConfig.java                   — Inference execution configuration
│   ├── InferenceBenchmarkService.java         — ONNX benchmark on startup
│   ├── OnnxBenchmarkService.java              — Per-model latency measurement
│   ├── LoadSheddingService.java               — Lag-based model selection
│   ├── GeoJumpDetector.java                   — Country change detection
│   └── VelocityDetector.java                  — Event velocity detection
│
├── kafka/                                     — Kafka producers & consumers
│   ├── AuditTrailConsumer.java                — Main Kafka listener (757 lines)
│   └── AlertPublisher.java                    — Publishes anomaly alerts to Kafka
│
├── mapper/                                    — Object mapping
│   ├── AnomalyAlertMapper.java                — Maps insight → AnomalyAlert DTO
│   └── GenericMapper.java                     — General-purpose mapping utilities
│
├── redis/                                     — Redis data access
│   ├── RedisCacheService.java                 — Generic Redis cache operations
│   └── RedisSessionBufferService.java         — Session event buffering in Redis
│
├── repository/                                — Spring Data JPA repositories
│   ├── AnomalyEventRepository.java            — Anomaly events CRUD
│   ├── SessionAnalysisRepository.java         — Session analysis CRUD
│   ├── UserRiskProfileRepository.java         — User risk profiles CRUD
│   ├── DashboardSnapshotRepository.java       — Dashboard snapshots CRUD
│   ├── NextEventPredictionRepository.java     — Next-event predictions CRUD
│   └── NextActionPredictionRepository.java    — Next-action predictions (unused)
│
└── service/                                   — Business logic services
    ├── EventIdempotencyService.java           — Deduplicates Kafka events via Redis
    ├── SessionFinalizationService.java        — Session end detection & state management
    ├── SessionFinalizationOrchestrator.java   — Coordinates finalization + alert publication
    ├── SessionRuleEvaluator.java              — Evaluates rules against enriched events
    ├── SessionRunningSummaryService.java      — Builds incremental session summaries
    ├── StatisticsService.java                 — Rolling live stats & user risk profiles
    ├── DashboardSnapshotService.java          — Builds & caches dashboard views
    ├── DashboardSnapshotPersistenceService.java — SQL persistence for dashboard snapshots
    ├── DashboardRefreshScheduler.java         — Scheduled dashboard view refreshes
    ├── AlertCacheService.java                 — Manages Redis alert caches
    ├── NextEventPredictionService.java        — Next-event prediction logic
    ├── ForecastRefreshService.java            — Forecast data refresh
    ├── ForecastRefreshScheduler.java          — Scheduled forecast refresh
    ├── LiveStatsScheduler.java                — Periodic live stats snapshot
    ├── PerformanceSummaryScheduler.java       — Periodic performance log
    ├── TrendPredictionScheduler.java          — System traffic anomaly + forecast
    └── ExpiredSessionFlushScheduler.java      — Periodic session cleanup
```

---

## ML Models & Detection Strategy

### Tier 1: Deterministic Rules (fast path)

Eight heuristic rules evaluated before ML inference. Results contribute directly to the risk score.

| Rule | Condition | Score Contribution |
|------|-----------|-------------------|
| `OFF_HOURS_ACCESS` | Activity between 02:00-04:00 UTC | 25.0 |
| `SENSITIVE_API_OFF_HOURS` | Sensitive API during off hours | 25.0 |
| `STATUS_CODE_BURST` | 3+ failures in 2 seconds | 35.0 |
| `UNUSUAL_DEVICE` | Unknown device fingerprint | 35.0 |
| `DEVICE_SWITCH` | Device change between events | 35.0 |
| `API_SCRAPING_PATTERN` | Systematic API probing | 35.0 |
| `COUNTRY_SWITCH` | Country code change | 40.0 |
| `UNUSUAL_COUNTRY` | Rare country code | 40.0 |
| `LARGE_DOWNLOAD` | 10+ downloads in 2 minutes | 40.0 |
| `DATA_EXTRACTION_PATTERN` | Bulk data access pattern | 40.0 |
| `RAPID_FIRE_EVENTS` | 3+ events in 2 seconds | 30.0 |
| `SKIP_LOGIN` | Actions without prior login | 30.0 |
| `IMPOSSIBLE_ENDPOINT_TRANSITION` | Markov P < 0.005 | 30.0 |
| `SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT` | Sensitive API outside hours | 30.0 |
| `SESSION_TIMEOUT` | Inter-event gap > 20 min | 20.0 |

### Tier 2: Tabular Anomaly Models

All served via custom tree-walking predictors (JSON/TXT) or ONNX Runtime:

| Model | Algorithm | Features | Output | Weight |
|-------|-----------|----------|--------|--------|
| **XGBoost** | Gradient boosted trees (JSON) | 14 scaled numerical | Probability [0,1] | 0.30 |
| **LightGBM** | Gradient boosted trees (TXT) | 14 scaled numerical | Probability [0,1] | 0.25 |
| **CatBoost** | Native CBM (ONNX) | 14 float features | Score (context only) | — |
| **OneClassSVM** | RBF-kernel (ONNX) | 14 scaled numerical | Novelty score (context only) | — |

### Tier 3: Sequence Models

| Model | Architecture | Purpose | Weight |
|-------|-------------|---------|--------|
| **Transformer** | ONNX transformer encoder | Next-event prediction → surprise score | 0.20 |
| **TCN** | Temporal Convolutional Network | Fast sequence scoring (load shedding) | 0.10 |

Sequence scores use: `aiRiskScore = 100 × (1 - exp(-surpriseScore / 5.0))`

### Context-Only Models (informational, not in fusion)

| Model | Purpose |
|-------|---------|
| **ExtraTrees (churn)** | Churn/dropoff probability |
| **Ridge / XGBoost (forecast)** | Volumetric event/rate forecasting |
| **K-Means (persona)** | Behavioral clustering (disabled) |

---

## Risk Fusion Formula

```
finalRisk = clamp(
    xgboostScore100 × 0.30 +
    lightgbmScore100 × 0.25 +
    transformerScore100 × 0.20 +
    tcnScore100 × 0.10 +
    ruleScore × 0.15 +
    businessContext × 0.00 +
    aggregationBoost,
    0.0, 100.0)
```

### Risk Tiers

| Level | Range | Description |
|-------|-------|-------------|
| LOW | 0.0 – 34.99 | Normal activity |
| MEDIUM | 35.0 – 59.99 | Suspicious (triggers anomaly flag) |
| HIGH | 60.0 – 79.99 | High risk (standalone alert threshold) |
| CRITICAL | 80.0 – 100.0 | Critical |

### Aggregation Boosts

| Condition | Boost |
|-----------|-------|
| 3+ rules triggered | +5 |
| `STATUS_CODE_BURST` + `RAPID_FIRE_EVENTS` | +3 |
| 10+ downloads + `SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT` | +5 |

### Alert Decision Logic

An alert is published if **any** condition is met:
1. `isAnomaly()` = true (finalRisk ≥ 35.0)
2. finalRisk ≥ 60.0 (sessionAlertRiskThreshold)
3. Session has not already had an alert (deduplication via Redis)

### Model Weight Renormalization

When a model is unavailable (timed out, null score), its weight is redistributed proportionally among remaining available models (controlled by `renormalizeMissingModelWeights: true`).

---

## Persistence Strategy

| Store | Role | Data |
|-------|------|------|
| **Redis** | Primary cache | Session buffers, insights, risk profiles, live stats, dashboard snapshots, active anomalies, next-action predictions |
| **SQL Server** | Durable storage | SessionAnalysis, AnomalyEvent, UserRiskProfile, DashboardSnapshot, NextEventPrediction |
| **Kafka** | Event bus + alerts | Input: topic-audit-trail. Output: topic-anomaly-alerts. DLQ: topic-audit-trail-dlq |

### Write Paths

| Data | Writer | When | Redis | SQL | Kafka |
|------|--------|------|-------|-----|-------|
| Session events | SessionBufferService | Per event | Yes | No | No |
| Session insight | DashboardSnapshotService | Per event | Yes | No | No |
| Anomaly alert | SessionFinalizationOrchestrator | Per event | Yes | Yes | Yes |
| Session analysis | SessionFinalizationService | Session end | No | Yes | No |
| Risk profile | StatisticsService | Periodic | Yes | Yes | No |
| Dashboard snapshot | DashboardRefreshScheduler | Periodic (15s) | Yes | Yes (fallback) | No |

### Hot Path Protection

- No SQL writes on the Kafka listener hot path (all SQL happens in scheduled jobs or session finalization)
- Dashboard SQL fallback is non-critical (failures logged, not propagated)

---

## Redis Key Layout

### Session State
| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `session:{insuredId}:{sessionId}` | 30m | Raw event buffer |
| `session:running-summary:v3_6:{sessionId}` | 30m | Incremental summary |
| `session:state:{insuredId}:{sessionId}` | 30m | Session lifecycle state |
| `session:sequence:v3_6:{sessionId}` | 30m | Sequence window |
| `session:risk:v3_6:{insuredId}:{sessionId}` | 30m | Session risk state |

### Insights & Predictions
| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `session:insight:{insuredId}:{sessionId}` | 2h | Live insight payload |
| `next_event_prediction:session:{sessionId}` | 2h | Next-event prediction |
| `next_event_prediction:insured:{insuredId}` | 2h | Per-user predictions |
| `anomaly:active:{insuredId}` | 30m | Active anomaly flag |
| `anomaly:detected:{insuredId}:{sessionId}` | 30m | Dedup guard |

### Live Alerts
| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `alerts:live:zset:v3_6` | 24h | Canonical live alert index (capped 5000) |
| `alerts:critical:zset:v3_6` | 24h | Critical subset |
| `alerts:high:zset:v3_6` | 24h | High subset |
| `alerts:user:{insuredId}:zset:v3_6` | 24h | Per-user subset |
| `alert:live:v3_6:{eventId}` | 24h | Alert payload JSON |

### Live Statistics
| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `stats:live:{date}` | 24h | Live stats snapshot |
| `stats:trend:{date}` | 24h | Trend stats |
| `stats:events:minute:{minute}` | 24h | Minute event count |
| `stats:alerts:minute:{minute}` | 24h | Minute alert count |
| `stats:actions:minute:{minute}` | 24h | Minute action count |
| `stats:countries:minute:{minute}` | 24h | Minute country count |
| `stats:ko:minute:{minute}` | 24h | Minute KO count |
| `stats:downloads:minute:{minute}` | 24h | Minute download count |

### Dashboard Snapshots
| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `dashboard:security-overview:v3_6` | 15m | Security overview |
| `dashboard:churn:v3_6` | 15m | Churn dashboard |
| `dashboard:forecast:v3_6` | 15m | Forecast dashboard |
| `dashboard:{view}` | 15m | Generic dashboard views |

### AI Runtime
| Key Pattern | TTL | Purpose |
|-------------|-----|---------|
| `ai:runtime:health:v3_6` | 15m | Model health + latency |
| `ai:sequence:field-coverage:v3_6` | 24h | Sequence field coverage |
| `ai:tabular:field-coverage:v3_6` | 24h | Tabular field coverage |
| `ai:model-latency:v3_6` | 15m | Per-model latency |
| `alert:llm-evidence:{eventId}` | 24h | LLM evidence payload |

---

## Database Schema

### `session_analysis` (64+ columns)

The core analytical output — one row per finalized session:

| Group | Key Columns |
|-------|-------------|
| Identity | `id`, `session_id`, `insured_id`, `persona`, `country_code` |
| Timing | `start_time`, `end_time`, `session_duration_seconds`, `avg/min/max_inter_action_seconds` |
| Actions | `first_action`, `last_action`, `total_events`, `unique_actions` |
| Network | `unique_ips`, `unique_devices`, `ip_changed`, `device_changed` |
| Status | `total_kos`, `total_oks`, `longest_ko_streak`, `has_login`, `has_logout` |
| Downloads | `total_download_actions`, `max_downloads_in_2_minutes` |
| Anomaly | `ping_pong_count`, `ended_abruptly`, `is_anomaly`, `primary_anomaly_type` |
| ML Scores | `anomaly_score`, `anomaly_probability`, `type_confidence`, `churn_probability`, `ensemble_risk_score` |
| Classification | `anomaly_type`, `risk_level`, `persona_cluster` |
| Markov | `path_deviation`, `transition_probability`, `transition_from_action`, `transition_to_action` |
| JSON blobs | `action_sequence_json`, `route_sequence_json`, `action_counts_json`, `anomaly_types_json`, `next_actions_json`, `rare_transitions_json`, `triggered_rules_json`, `feature_contributions_json`, `llm_explanation_evidence_payload_json` |

### `anomaly_events` (30+ columns)

| Group | Key Columns |
|-------|-------------|
| Identity | `id`, `insured_id`, `session_id`, `event_id` |
| Timing | `event_time`, `detected_at` |
| Classification | `anomaly_tier` (ANOMALY/SYSTEM/RULE), `anomaly_type`, `anomaly_flag` |
| Scores | `anomaly_score`, `anomaly_probability`, `type_confidence`, `risk_score`, `churn_probability` |
| Model data | `rule_type`, `model_artifact`, `persona_cluster` |
| Context | `model_scores_json`, `model_contributions_json`, `event_json` |

### `user_risk_profile`

| Column | Description |
|--------|-------------|
| `insured_id` | User identifier (unique) |
| `last_updated` | Profile refresh timestamp |
| `anomaly_count_7d/30d` | Anomaly statistics |
| `anomaly_rate_30d` | Anomaly rate |
| `risk_tier` | HIGH / MEDIUM / LOW |
| `sessions_7d/30d` | Session volume |
| `consecutive_clean_sessions` | Clean streak |

### `dashboard_snapshots`

| Column | Description |
|--------|-------------|
| `view_name` | Logical view (e.g. "security-overview") |
| `snapshot_key` | Unique key within view |
| `payload_json` | Full JSON payload |
| `payload_hash` | SHA-256 for change detection |
| `source` | Origin identifier |

---

## Configuration Reference

### Core Infrastructure

| Key | Default | Description |
|-----|---------|-------------|
| `spring.datasource.url` | `jdbc:sqlserver://localhost:1433;databaseName=NoveoCareDB` | SQL Server JDBC URL |
| `spring.datasource.username` | `app_user` | DB username |
| `spring.kafka.bootstrap-servers` | `localhost:9092` | Kafka broker |
| `spring.kafka.consumer.group-id` | `data-processor-group` | Consumer group |
| `spring.data.redis.host` | `localhost` | Redis host |

### AI Inference

| Key | Default | Description |
|-----|---------|-------------|
| `app.ai.inference-enabled` | `true` | Master toggle |
| `app.ai.tabular-anomaly.xgboost-enabled` | `true` | XGBoost toggle |
| `app.ai.tabular-anomaly.lightgbm-enabled` | `true` | LightGBM toggle |
| `app.ai.tabular-anomaly.catboost-enabled` | `true` | CatBoost toggle (context only) |
| `app.ai.tabular-anomaly.oneclasssvm-enabled` | `true` | OneClassSVM toggle (context only) |
| `app.ai.sequence.enabled` | `true` | Sequence detection toggle |
| `app.ai.sequence.transformer-enabled` | `true` | Transformer toggle |
| `app.ai.sequence.tcn-enabled` | `true` | TCN toggle |
| `app.ai.sequence.run-both` | `false` | Run both Transformer and TCN |
| `app.ai.churn.enabled` | `true` | Churn prediction toggle |
| `app.ai.forecast.enabled` | `true` | Forecast toggle |

### Risk Fusion

| Key | Default | Description |
|-----|---------|-------------|
| `app.ai.risk-fusion.xgboost-weight` | `0.30` | XGBoost fusion weight |
| `app.ai.risk-fusion.lightgbm-weight` | `0.25` | LightGBM fusion weight |
| `app.ai.risk-fusion.transformer-weight` | `0.20` | Transformer fusion weight |
| `app.ai.risk-fusion.tcn-weight` | `0.10` | TCN fusion weight |
| `app.ai.risk-fusion.rules-weight` | `0.15` | Rule risk fusion weight |
| `app.ai.risk-fusion.medium-threshold` | `35.0` | MEDIUM tier boundary |
| `app.ai.risk-fusion.high-threshold` | `60.0` | HIGH tier boundary |
| `app.ai.risk-fusion.critical-threshold` | `80.0` | CRITICAL tier boundary |

### Live Fast Mode

| Key | Default | Description |
|-----|---------|-------------|
| `app.ai.live-fast-mode.enabled` | `true` | Enable inference budget capping |
| `app.ai.live-fast-mode.max-inference-ms-budget` | `500` | Max inference time per event |
| `app.ai.live-fast-mode.prefer-lightgbm-only` | `true` | Use only LightGBM when over budget |
| `app.ai.live-fast-mode.skip-sequence` | `false` | Skip all sequence models |
| `app.ai.live-fast-mode.skip-transformer` | `false` | Skip Transformer (use TCN) |

### Scheduling

| Key | Default | Description |
|-----|---------|-------------|
| `app.scheduling.live-stats-fixed-rate-ms` | `10000` | Live stats refresh interval |
| `app.scheduling.trend-cron` | `0 */2 * * * *` | Forecast refresh cron |
| `app.performance.dashboard-refresh.dashboard-refresh-interval-ms` | `15000` | Dashboard refresh interval |

### Session Finalization

| Key | Default | Description |
|-----|---------|-------------|
| `app.session.finalization.inactivity-timeout-seconds` | `1200` | 20 min inactivity = session end |
| `app.session.finalization.max-open-duration-seconds` | `7200` | 2 hour max session |
| `app.session.finalization.expired-flush-interval-ms` | `30000` | Cleanup interval |

### Feature Engineering

| Key | Default | Description |
|-----|---------|-------------|
| `app.features.download-window-seconds` | `120` | Sliding download burst window |
| `app.features.rapid-action-seconds` | `7` | Rapid action threshold |
| `app.features.session-alert-risk-threshold` | `60.0` | Standalone alert threshold |

---

## Local Development

### Prerequisites

- Java 25 (JDK 25+)
- Maven 3.9+ (wrapped via `mvnw.cmd`)
- Docker Desktop
- Python 3.10+ (for simulators)

### Infrastructure

```powershell
# Create shared Docker network
docker network create shared-net

# Start core services (Kafka KRaft + Redis + Kafka Connect)
# From ../Infra/
docker compose -f ../Infra/docker-compose.yml up -d

# Optional: Monitoring
docker compose -f ../monitoring/docker-compose.yml up -d

# Optional: ELK
docker compose -f ../elk/docker-compose.yml up -d
```

### Build & Run

```powershell
cd "Data Processor"

# Compile
.\mvnw.cmd compile

# Unit tests
.\mvnw.cmd test

# Package
.\mvnw.cmd package -DskipTests

# Run
.\mvnw.cmd spring-boot:run
```

### Simulation (Live Data Feed)

```powershell
cd ../scripts

# Live Kafka replay
python simulator_final_revised.py --kafka-bootstrap localhost:9092 --topic topic-audit-trail --speed 60

# Continous anomalous session emitter
python anomaly_kafka_producer.py --kafka-bootstrap localhost:9092 --topic topic-audit-trail --speed 10
```

### Docker Build (Jib)

```powershell
cd "Data Processor"
.\mvnw.cmd compile jib:dockerBuild
# Image: data-processor:latest
# Base: eclipse-temurin:25-jre
# JVM: -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | JDBC SQL Server localhost | Database JDBC URL |
| `DB_USERNAME` | `app_user` | Database user |
| `DB_PASSWORD` | `change_me` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `KAFKA_CONSUMER_GROUP_ID` | `data-processor-group` | Consumer group |
| `KAFKA_CONSUMER_CONCURRENCY` | `5` | Listener threads |
| `AUDIT_TRAIL_TOPIC` | `topic-audit-trail` | Input topic |
| `ANOMALY_ALERTS_TOPIC` | `topic-anomaly-alerts` | Output topic |
| `AUDIT_TRAIL_DLQ_TOPIC` | `topic-audit-trail-dlq` | Dead-letter topic |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `AI_INFERENCE_ENABLED` | `true` | Master inference toggle |
| `AI_LIVE_FAST_MODE_ENABLED` | `true` | Fast mode toggle |
| `TRACE_EVENT_PROCESSING` | `false` | Per-event trace logging |

---

## Docs

Additional documentation is available in the `docs/` directory:

| Document | Description |
|----------|-------------|
| `docs/scoring-reference.md` | Full scoring reference — fusion formula, per-model details, threshold logic, ALERT_DECISION logs |
| `docs/persistence-strategy.md` | Redis + SQL durable architecture, dashboard snapshot fallback, write paths |
| `docs/alert-payload-contract.md` | Complete AnomalyAlert DTO schema, Redis cached payload structure, SQL column mapping |
