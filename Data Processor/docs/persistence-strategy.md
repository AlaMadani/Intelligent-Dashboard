# Persistence Strategy — Data Processor

## Architecture

```
Kafka audit events
  -> dataprocessor worker
      -> consumes events
      -> computes model/rule scores
      -> computes final session risk
      -> persists durable analysis to SQL Server
      -> writes fast dashboard/cache views to Redis
      -> publishes anomaly alerts to Kafka
      -> persists dashboard snapshots to SQL Server for durable fallback
  -> api-service
      -> reads Redis first for fast dashboard/API responses
      -> falls back to SQL Server if Redis misses
      -> exposes REST responses to frontend
  -> frontend
      -> displays api-service data
```

## Redis + SQL Durable Architecture

The goal is to make `api-service` capable of:

1. Read Redis key
2. If present -> return data with `source=redis`
3. If missing -> read `dashboard_snapshots` by `view_name` + `snapshot_key`
4. Return data with `source=sql_fallback`
5. Optionally rehydrate Redis

This document covers only the **dataprocessor side** of the strategy.

## Data Classification

### A. Redis + SQL (durable snapshots)

| View | Snapshot Key | Persisted From | Notes |
|------|-------------|----------------|-------|
| security-overview | `security-overview:latest` | `DashboardSnapshotService.refreshSecurityOverview()` | Contains totalEvents, anomalyRate, criticalAlerts, topRules, modelHealth |
| alerts | `alerts:latest` | `DashboardSnapshotService.cacheDashboard("alerts", ...)` | Precomputed sorted/merged alert feed |
| risky-sessions | `risky-sessions:latest` | `DashboardSnapshotService.cacheDashboard("risky-sessions", ...)` | Sorted risky session list |
| churn-dashboard | `churn-dashboard:latest` | `DashboardSnapshotService.refreshChurnDashboard()` | Churn distribution, top users |
| forecast-dashboard | `forecast-dashboard:latest` | `DashboardSnapshotService.refreshForecastDashboardV36()` | Forecast prediction data |
| cluster-mix | `cluster-mix:latest` | `DashboardSnapshotService.cacheDashboard("cluster-mix", ...)` | Persona cluster distribution |
| drop-offs | `drop-offs:latest` | `DashboardSnapshotService.cacheDashboard("drop-offs", ...)` | Drop-off action analysis |
| path-deviations | `path-deviations:latest` | `DashboardSnapshotService.cacheDashboard("path-deviations", ...)` | Path deviation stats |
| forecasts (series) | `forecasts:latest` | `DashboardSnapshotService.cacheDashboard("forecasts", ...)` | Forecast time series |
| model-health / runtime-health | `model-health:latest` | `DashboardSnapshotService.cacheDashboard("model-health", ...)` | Runtime health summary |
| field-coverage | embedded in model-health snapshot | `DashboardSnapshotService.cacheDashboard("model-health", ...)` | Contained within model-health payload |

### B. SQL Only (already persisted durably)

| Table | Data | Persisted From |
|-------|------|----------------|
| `anomaly_events` | Alert/anomaly event history | `AlertPublisher` / session finalization |
| `session_analysis` | Finalized session analysis | `SessionFinalizationService` |
| `user_risk_profile` | Rolling user risk statistics | `StatisticsService` |
| `next_action_predictions` | Next action prediction (currently empty) | Currently disabled |

These tables do NOT need duplicate dashboard snapshot entries unless the view payload is materially different or expensive to reconstruct from SQL.

### C. Redis Only (ephemeral/runtime/transient)

| Key Pattern | Reason |
|-------------|--------|
| `session:*` | Live session event buffers, dedup sets, sequence window state |
| `session:insight:*` | Per-session insight cache (TTL 2h) |
| `user:360:*` | Per-user 360 payload cache (TTL 2h) |
| `session:state:*` | Live session state |
| `session:risk:*` | Live session risk |
| `session:running-summary:*` | Live running summary |
| `alerts:live:v3_6` | Fast live alert list for dashboard |
| `alerts:user:*` | Per-user alert cache |
| `alerts:live:eventIds:*` | Alert dedup set |
| `stats:*` | Live minute/day rolling statistics counters |
| `ai:model-latency:v3_6` | Model latency snapshot (transient) |
| `risk:*` | Per-user risk cache |
| `next_actions:*` | Next action predictions cache (currently disabled) |
| `anomaly:*` | Active/detected anomaly markers |
| `session:first-event:*` | Per-session first event cache |
| `session:insight:index*` | Active insight index sets |
| `alert:investigation:*` | Investigation payload cache |
| `alert:llm-evidence:*` | LLM evidence payload cache |

## SQL Schema — `dashboard_snapshots`

### Columns

| Column | Type | Notes |
|--------|------|-------|
| `id` | BIGINT IDENTITY | Primary key |
| `schema_version` | NVARCHAR(32) | Schema version (e.g. "v3.6.1") |
| `view_name` | NVARCHAR(128) | Logical view name (e.g. "security-overview") |
| `snapshot_key` | NVARCHAR(256) | Unique key within view (e.g. "security-overview:latest") |
| `snapshot_date` | DATE | Date of the snapshot for partitioning/queries |
| `snapshot_timestamp` | DATETIME2 | When the snapshot was captured |
| `payload_json` | NVARCHAR(MAX) | Full JSON payload |
| `payload_hash` | NVARCHAR(128) | SHA-256 hex hash for change detection |
| `source` | NVARCHAR(64) | Origin (e.g. "dashboard_refresh") |
| `created_at` | DATETIME2 | Row creation timestamp |
| `updated_at` | DATETIME2 | Last update timestamp |
| `expires_at` | DATETIME2 | Optional expiry (not enforced by DB) |

### Unique Constraint

```
UNIQUE (view_name, snapshot_key)
```

### Indexes

| Index | Columns | Purpose |
|-------|---------|---------|
| `idx_ds_view_key` | `(view_name, snapshot_key)` UNIQUE | Fast lookup by view + key |
| `idx_ds_view_timestamp` | `(view_name, snapshot_timestamp DESC)` | Latest snapshot per view |
| `idx_ds_snapshot_date` | `(snapshot_date)` | Date-range queries |
| `idx_ds_updated_at` | `(updated_at DESC)` | Recent updates ordering |

## Persistence Behavior

### Flow

```
dashboard refresh tick
  -> build view payload
  -> write Redis (fast cache)
  -> persist SQL dashboard_snapshots row (durable fallback)
  -> log timing
```

### Upsert Logic

- Same `view_name` + `snapshot_key` -> UPDATE `payload_json`, `snapshot_timestamp`, `payload_hash`, `updated_at`
- Different -> INSERT new row

### Payload Hash Optimization

- Compute SHA-256 hex hash of serialized `payload_json`
- If hash unchanged from existing row, skip SQL write (logged as `SKIPPED_UNCHANGED`)
- Reduces unnecessary SQL writes when payload content has not changed

### Non-Critical Writes

- SQL snapshot write failures do NOT fail the dashboard refresh
- SQL snapshot write failures do NOT fail Kafka event processing
- Failures are logged with `DASHBOARD_SNAPSHOT_PERSIST_FAILED`

## Performance Constraints

### Hot Path Protection

| Rule | Implementation |
|------|----------------|
| No SQL snapshot writes from Kafka hot thread | All SQL snapshot writes happen in dashboard refresh scheduler |
| SQL failure does not fail event processing | `persistSnapshot()` catches all exceptions |
| Rate-limited | Dashboard refresh already rate-limited by min interval configs |
| Payload hash optimization | Skip SQL write if payload unchanged |

### Log Messages

| Log Tag | When |
|---------|------|
| `DASHBOARD_SNAPSHOT_PERSIST` | Successful SQL write (or skipped unchanged) |
| `DASHBOARD_SNAPSHOT_PERSIST_FAILED` | SQL write failure |

### Counters

| Counter | Location |
|---------|----------|
| `dashboardSnapshotSqlWriteSuccessTotal` | `DashboardSnapshotPersistenceService` |
| `dashboardSnapshotSqlWriteFailureTotal` | `DashboardSnapshotPersistenceService` |
| `dashboardSnapshotSqlLastWriteAt` | `DashboardSnapshotPersistenceService` |
| `dashboardSnapshotSqlLastFailureAt` | `DashboardSnapshotPersistenceService` |

These counters are logged in the periodic performance summary and included in the model health snapshot.

## Api-Service Fallback Strategy

### Expected Behavior

1. Read Redis key for the requested view
2. If present -> return data with `source=redis`
3. If Redis miss -> query `dashboard_snapshots` by `view_name` + `snapshot_key`
4. If SQL row found -> return data with `source=sql_fallback`
5. Optionally rehydrate Redis with the SQL payload
6. If no SQL row found -> return 404 or fallback empty

### SQL Fallback Mapping

| Frontend View | Redis Key | SQL `view_name` | SQL `snapshot_key` |
|---------------|-----------|-----------------|-------------------|
| Security Overview | `dashboard:security-overview:v3_6` | `security-overview` | `security-overview:latest` |
| Churn Dashboard | `dashboard:churn:v3_6` | `churn-dashboard` | `churn-dashboard:latest` |
| Forecast Dashboard | `dashboard:forecast:v3_6` | `forecast-dashboard` | `forecast-dashboard:latest` |
| Live Alerts | `dashboard:alerts` | `alerts` | `alerts:latest` |
| Risky Sessions | `dashboard:risky-sessions` | `risky-sessions` | `risky-sessions:latest` |
| Runtime Health | `ai:runtime:health:v3_6` | `model-health` | `model-health:latest` |
| Cluster Mix | `dashboard:cluster-mix` | `cluster-mix` | `cluster-mix:latest` |
| Drop Offs | `dashboard:drop-offs` | `drop-offs` | `drop-offs:latest` |
| Path Deviations | `dashboard:path-deviations` | `path-deviations` | `path-deviations:latest` |
| Forecast Time Series | `dashboard:forecasts` | `forecasts` | `forecasts:latest` |
| User 360 | `user:360:{insuredId}` | N/A | N/A — reconstruct from `user_risk_profile` + `session_analysis` + `anomaly_events` |
| User Alerts | `alerts:user:{insuredId}` | N/A | N/A — reconstruct from `anomaly_events` where `insured_id = ?` |
| Critical Alerts | `alerts:critical:v3_6` | N/A | N/A — reconstruct from `anomaly_events` where `risk_level = 'CRITICAL'` or `final_risk_score >= 80` |

## Existing Table Review

### `anomaly_events`

**Status**: Adequate for V3.6.1 needs.

**Has**: `schema_version` (via `v36_runtime_version`), `risk_level`, `risk_tier` (alias), `final_risk_score`, `model_scores_json`, `model_contributions_json`, `triggered_rules_json`, `event_json`, `detected_at`, `insighted_id`, `session_id`, `event_id`, `churn_probability`, `churn_risk_level`, `ai_risk_score`, `rule_risk_score`, `xgboost_anomaly_score_100`, `lightgbm_alert_score_100`, `transformer_risk_score_100`, `tcn_risk_score_100`, `llm_evidence_payload_available`, `llm_evidence_payload_redis_key`

**Missing**: `scoring_version`, `risk_fusion_version`, `anomaly_type_evidence_json`, `anomaly_type_confidence`, `anomaly_type_source`

Some of these are present: `anomaly_type_evidence_json` IS present, `anomaly_type_confidence` IS present, `anomaly_type_source` IS present. The `event_id` alone is not globally unique across test runs — prefer querying by `event_id + v36_runtime_version` or composite `event_id + session_id + insured_id`.

### `session_analysis`

**Status**: Adequate for V3.6.1 needs.

**Has**: `v36_runtime_version`, `model_contributions_json`, `investigation_payload_json`, `llm_explanation_evidence_payload_json`, `top_contributing_features_json`, `triggered_rules_json`, `action_sequence_json`, `route_sequence_json`, `created_at`, `start_time`, `end_time`

**Missing**: `updated_at`, `finalized_at`

**Caveat**: Old pre-Level-E rows will NOT be recomputed automatically. The duplicate TCN contribution bug affected rows generated before the fix. These rows may have inflated `final_risk_score`. No automatic backfill is planned; consumers should be aware.

### `user_risk_profile`

**Status**: Adequate.

**Has**: `insured_id` (unique), `last_updated`, `anomaly_count_7d`, `anomaly_count_30d`, `anomaly_rate_30d`, `risk_tier`, `sessions_7d`, `sessions_30d`, `most_frequent_action_30d`, `avg_session_duration_30d`, `consecutive_clean_sessions`

**Missing**: `profile_window_start`, `profile_window_end`, `max_risk`, `average_risk`

The profile is computed incrementally. Current fields cover the essential dashboard needs.

### `next_action_predictions`

**Status**: Currently always empty. Feature disabled in config.

**Table structure**: `id`, `insured_id` (unique), `session_id`, `predicted_at`, `top3_actions_json`

**Future evolution**: Consider generalizing to `sequence_predictions` with support for:
- `prediction_type` (NEXT_ACTION, NEXT_API_TEMPLATE, NEXT_API_FAMILY, NEXT_PAGE, NEXT_ROUTE)
- `predicted_action`, `predicted_api_template`, `predicted_api_family`, `predicted_page`, `predicted_route`
- `top_k_predictions_json` (JSON array of top-K predictions)
- `confidence` (overall confidence score)
- `model_name`, `model_artifact`
- `source_event_id`, `insured_id`, `session_id`
- `created_at`, `expires_at`

## Transformer Prediction Capability Note

The existing Transformer ONNX model already produces:

- **Categorical logits**: For 15 categorical fields including `action`, `apiTemplate`, `apiFamily`, `page`, `route`, `controller`, `httpMethod`, `status`, `country`, `device`, `browser`, `os`, `riskScoreBucket`, `hourOfDayBucket`, `dayOfWeekBucket`
- **Continuous predictions**: 9 continuous fields including `timeDelta`, `requestSize`, `responseSize`, and 6 business-hour context features

The same Transformer can likely support:

| Prediction | Source | Current Status |
|------------|--------|----------------|
| Next action | Categorical logits for `action` field | Feasible, not implemented |
| Next API template | Categorical logits for `api_template` field | Feasible, not implemented |
| Next API family | Categorical logits for `api_family` field | Feasible, not implemented |
| Next page/route | Categorical logits for `page`/`route` field | Feasible, not implemented |
| Next time delta | Continuous prediction for `timeDelta` | Feasible, not implemented |
| Top-K next behavior | Top-K argmax of logits | Feasible, not implemented |

**Important**: Do NOT mix prediction scores with anomaly risk scores unless separately designed. Predictions are a separate concern from security anomaly detection.

## Next Steps

1. Api-service implements Redis-first, SQL-fallback read pattern
2. Api-service optionally rehydrates Redis from SQL on fallback
3. Consider periodic cleanup of old `dashboard_snapshots` rows (if TTL-based expiry is desired)
4. Consider adding `field-coverage` and `model-latency` as independent snapshot views
5. Future: generalize `next_action_predictions` table to `sequence_predictions` with multi-field, top-K predictions
