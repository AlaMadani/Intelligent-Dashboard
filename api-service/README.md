# API Service — NoveoCare Analytics REST API

A **Spring Boot 4.0.3** read-side REST API that serves real-time analytics, session data, anomaly events, risk profiles, and AI-powered explanations to the Quasar frontend dashboard. Reads from Redis (written by the **Data Processor** worker) and SQL Server, with optional Gemini AI integration for natural-language anomaly explanations.

## V3.6.1 Role and Boundary

`api-service` is the **read-side only** service in the three-project architecture:

- `dataprocessor` consumes raw Kafka audit events, runs the V3.6 hybrid AI runtime, persists processed SQL rows, writes Redis snapshots, handles event idempotency (Redis/SQL dedupe), session finalization, and creates LLM evidence payloads.
- `api-service` reads SQL Server and Redis, exposes REST/SSE endpoints, and calls Gemini only when the frontend explicitly requests an explanation.
- `frontend` calls `api-service` only.

`api-service` remains read-only. It does **not**:
- Run ONNX, XGBoost, LightGBM, CatBoost, ExtraTrees, Ridge, sequence models, or feature-vector builders
- Recompute risk or finalize sessions
- Deduplicate source data as business logic (defensive read-side eventId dedup may log warnings)
- Call `dataprocessor` directly or consume raw Kafka scoring events
- Own database migrations (Liquibase stays disabled by default)

### V3.6.1 Redis Keys Read

The service now prefers these versioned keys and falls back to SQL or legacy keys where appropriate:

| Area | Redis keys |
|------|------------|
| Runtime | `ai:runtime:health:v3_6` (includes optional kafka/idempotency/performance/stats/nextActionPrediction/sessionFinalization sections), `ai:sequence:field-coverage:v3_6`, `ai:tabular:field-coverage:v3_6`, `ai:model-latency:v3_6` |
| Dashboards | `dashboard:security-overview:v3_6`, `dashboard:churn:v3_6`, `dashboard:forecast:v3_6` |
| Alerts | `alerts:live:v3_6`, `alerts:critical:v3_6`, `alerts:user:{insuredId}` |
| Investigation | `alert:investigation:{eventId}` |
| LLM evidence | `alert:llm-evidence:{eventId}` |
| User 360 | `user:360:{insuredId}` |
| Session V3.6 | `session:sequence:v3_6:{sessionId}`, `session:scores:v3_6:{sessionId}`, `session:risk:v3_6:{insuredId}:{sessionId}` |

Legacy keys such as `stats:live:{date}`, `dashboard:forecasts`, `dashboard:forecast-series`, `session:insight:{insuredId}:{sessionId}`, `risk:{insuredId}`, `next_actions:{insuredId}`, `anomaly:active:{insuredId}`, and `dashboard:{view}` remain supported for compatibility.

### New V3.6.1 Endpoints

All endpoints remain under `/api/v1` and return the existing `ApiResponse<T>` envelope unless otherwise noted. V3.6.1 payloads include `schemaVersion: "v3.6.1"`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/ai/runtime-health` | V3.6.1 runtime health (kafka/idempotency/performance/stats/nextActionPrediction/sessionFinalization) from Redis with UNKNOWN fallback |
| GET | `/security/overview` | Security overview snapshot with SQL/legacy fallback |
| GET | `/security/diagnostics` | Runtime health (all sections), field coverage, latency, warnings; top-level convenience fields for kafka/idempotency/performance/stats/sessionFinalization |
| GET | `/alerts/live` | V3.6.1 live alerts with filters and SQL fallback |
| GET | `/alerts/critical` | Critical alerts from Redis or SQL fallback |
| GET | `/alerts/{eventId}` | Alert investigation detail from Redis or SQL payload fallback; includes sessionLifecycle when available |
| GET | `/explanations/alerts/{eventId}/evidence` | Raw V3.6.1 LLM evidence payload |
| GET | `/explanations/alerts/{eventId}` | Cached explanation only |
| POST | `/explanations/alerts/{eventId}` | Explicit on-demand Gemini/fallback explanation generation |
| GET | `/users/{insuredId}/360` | User 360 from Redis or existing user dashboard fallback |
| GET | `/users/{insuredId}/alerts` | User alerts from Redis or SQL fallback |
| GET | `/churn/dashboard` | Churn dashboard from Redis or stored SQL fields |
| GET | `/churn/users` | Stored churn-risk users |
| GET | `/forecast/dashboard` | Forecast dashboard from Redis or legacy trend fallback |
| GET | `/ai/final-winners` | Classpath report if copied into api-service |
| GET | `/ai/reports` | Report metadata |

The existing `GET /api/v1/anomalies/{id}/explain` route is preserved. It now attempts to resolve the anomaly's `eventId`, use V3.6.1 evidence when available, and otherwise falls back to the previous multi-source explanation behavior.

### LLM Explanation Flow

Preferred generation is:

1. Frontend calls `POST /api/v1/explanations/alerts/{eventId}`.
2. `api-service` checks the V3.6.1 explanation cache.
3. It reads `alert:llm-evidence:{eventId}` from Redis.
4. It falls back to SQL `llm_explanation_evidence_payload_json`.
5. It validates `schemaVersion = "v3.6.1"`.
6. It builds a grounded prompt from evidence only.
7. It calls Gemini only when configured and explicitly requested by POST.
8. If Gemini is unavailable, it returns a deterministic fallback explanation from the evidence.
9. It caches the response under `ai:explanation:v3_6:alert:{eventId}:...`.

`GET /api/v1/explanations/alerts/{eventId}` is cache-only and does not call Gemini.

### V3.6.1 Configuration

```yaml
app:
  api:
    schema-version: "v3.6.1"
  v36:
    redis:
      prefer-v36-keys: true
      fallback-to-legacy: true
    explanations:
      enabled: true
      provider: gemini
      cache-enabled: true
      cache-ttl-hours: 24
      max-evidence-size-kb: 128
      generate-on-get: false
      default-style: security_analyst
      default-language: en
    endpoints:
      expose-reports: true
      expose-diagnostics: true
```

`GEMINI_API_KEY` defaults to empty. When absent, explanation generation returns deterministic fallback text rather than calling an LLM.

See [docs/api-service-v36-endpoints.md](docs/api-service-v36-endpoints.md) for endpoint examples.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Data Flow](#data-flow)
3. [API Endpoints](#api-endpoints)
4. [SSE Live Streaming](#sse-live-streaming)
5. [Caching Strategy](#caching-strategy)
6. [Gemini AI Explanations](#gemini-ai-explanations)
7. [DTO Layer](#dto-layer)
8. [Mapper Layer](#mapper-layer)
9. [Redis Key Layout](#redis-key-layout)
10. [Configuration Reference](#configuration-reference)
11. [Development Setup](#development-setup)
12. [Testing](#testing)
13. [Docker Build](#docker-build)

---

## Architecture Overview

```
                    ┌──────────────────────────────────────┐
                    │            Quasar Frontend            │
                    │  (Vue 3 + vue-echarts + Pinia)        │
                    └──────────┬───────────────────────────┘
                               │ HTTP REST + SSE
                               ▼
              ┌────────────────────────────────────┐
              │         API Service (Port 8081)     │
              │                                    │
              │  AnalyticsController               │
              │  ├─ /api/v1/sessions/*              │
              │  ├─ /api/v1/anomalies/*             │
              │  ├─ /api/v1/users/*                 │
              │  ├─ /api/v1/stats/*                 │
              │  ├─ /api/v1/trends/*                │
              │  ├─ /api/v1/dashboard/*             │
              │  └─ /api/v1/stream/live (SSE)       │
              │                                    │
              │  Services ───► Redis ◄─────────────┐│
              │       │                            ││
              │       └──► SQL Server              ││
              │                                    ││
              │  AnomalyExplanationService         ││
              │  └──► Gemini (optional)            ││
              └────────────────────────────────────┘│
                                                    │
                                Data Processor writes│
                                to these Redis keys  │
                                                    ▼
              ┌──────────────────────────────────────────┐
              │            Data Processor Worker          │
              │  (Kafka -> Feature Engineering -> ONNX ML  │
              │   -> Redis + SQL Server)                   │
              └──────────────────────────────────────────┘
```

The API Service is a **stateless REST API** that:

- Reads **real-time stats** from Redis (populated by the Data Processor every 5 seconds)
- Reads **forecast/trend data** from Redis (populated by the Data Processor every 30 seconds via Prophet)
- Reads **persistent session and anomaly data** from SQL Server
- **Aggregates** data from multiple sources into a unified CommandCenterDto for the dashboard
- **Streams** live updates to connected clients via **Server-Sent Events** (SSE)
- Generates **AI-powered natural-language explanations** for anomaly events via Google Gemini (optional, falls back to heuristic explanations)

---

## Data Flow

### Redis Bridge (Shared with Data Processor)

The API Service and Data Processor share Redis keys. The **Data Processor writes**, and the **API Service reads**:

| What | Data Processor Writes | API Service Reads |
|------|----------------------|-------------------|
| Live stats | stats:live:{date} every 5s | StatsService.getLiveStats() |
| Trend/forecast | dashboard:forecasts + dashboard:forecast-series every 30s | StatsService.getTrendStats() |
| Session insight | session:insight:{insuredId}:{sessionId} per session | SessionInsightReadService.getInsight() |
| Risk profile | risk:{insuredId} per user update | RiskProfileService.getRiskProfile() |
| Next actions | next_actions:{insuredId} per session | NextActionPredictionService.getPrediction() |
| Active anomaly | anomaly:active:{insuredId} per anomaly | ActiveAnomalyService.getActiveAnomaly() |
| Dashboard snapshots | dashboard:{view} per refresh cycle | DashboardReadService.getSnapshot() |

### Redis PubSub Bridge

The Data Processor publishes refresh notifications to the LIVE_STATS Redis PubSub channel. The API Service's RedisDashboardRefreshListener picks up these notifications and broadcasts them to all SSE subscribers.

```
Data Processor                Redis PubSub                API Service
     │                             │                           │
     ├── publishes ──────────────► │                           │
     │  {"refresh":"stats"}       │                           │
     │                            ├── RedisDashboardRefreshListener
     │                            │    ├── broadcast SSE "stats"
     │                            │    └── broadcast SSE "refresh"
     │                            │                           │
     ├── publishes ──────────────► │                           │
     │  {"refresh":"forecasts"}   │                           │
     │                            └── broadcast SSE "refresh"
```

### SQL Server Read Path

SQL Server serves as the **persistent fallback** for data not in Redis:

- SessionAnalysis -- paginated session history with filters
- AnomalyEvent -- paginated anomaly alert history with filters
- UserRiskProfile -- persisted risk profile per user
- NextActionPrediction -- latest top-3 next actions per user

The API Service uses a **service -> repository -> entity -> mapper -> DTO** stack for SQL reads, always preferring Redis when available.

---

## API Endpoints

All endpoints under /api/v1. Return ApiResponse<T> with optional PaginationMeta.

### Session Endpoints

| Method | Path | Description | Pagination |
|--------|------|-------------|------------|
| GET | /sessions | List sessions (filters: insuredId, from/to, isAnomaly, signature) | Yes |
| GET | /sessions/{id} | Get single session by PK | -- |
| GET | /sessions/active | List live active sessions from Redis | -- |
| GET | /sessions/{insuredId}/{sessionId}/insight | Get live session insight from Redis | -- |

### User Endpoints

| Method | Path | Description | Pagination |
|--------|------|-------------|------------|
| GET | /users/{insuredId}/sessions | Session history for a user | Yes |
| GET | /users/{insuredId}/dashboard | Aggregated user dashboard (risk + sessions + anomalies + next actions) | -- |

### Risk Profile Endpoints

| Method | Path | Description | Pagination |
|--------|------|-------------|------------|
| GET | /risk-profiles | List all risk profiles | Yes |
| GET | /risk-profiles/{insuredId} | Get risk profile for one user | -- |

### Anomaly Endpoints

| Method | Path | Description | Pagination |
|--------|------|-------------|------------|
| GET | /anomalies | List anomaly events (filters: insuredId, from/to, tier, type) | Yes |
| GET | /anomalies/{id} | Get single anomaly event | -- |
| GET | /anomalies/{id}/investigation | Deep-dive aggregation (session + timeline + related anomalies) | -- |
| GET | /anomalies/{id}/explain | AI-powered explanation via Gemini | -- |
| GET | /anomalies/active/{insuredId} | Get active ongoing anomaly for a user | -- |

### Next Action Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | /next-actions/{insuredId} | Get top-3 Markov-predicted next actions for a user |

### Stats & Trend

| Method | Path | Description |
|--------|------|-------------|
| GET | /stats/live | Live stats snapshot (optional ?date=YYYY-MM-DD) |
| GET | /stats/summary | Aggregated summary counts |
| GET | /trends/forecast | Prophet forecast data (optional ?date=YYYY-MM-DD) |

### Dashboard

| Method | Path | Description |
|--------|------|-------------|
| GET | /dashboard/command-center | Full aggregated dashboard payload |
| GET | /dashboard/{view} | Single snapshot. Valid views: alerts, risky-sessions, cluster-mix, drop-offs, path-deviations, forecasts, forecast-series |

### Streaming & Health

| Method | Path | Description |
|--------|------|-------------|
| GET | /stream/live | SSE stream (live stats every 10s) |
| GET | /health | Health check (Redis + DB, returns UP/DEGRADED) |

---

## SSE Live Streaming

**Endpoint:** GET /api/v1/stream/live produces text/event-stream

### Events

| Name | Payload | Frequency | Description |
|------|---------|-----------|-------------|
| stats | StatsApiResponseDto | Every 10s + immediate on subscribe | Live stats snapshot |
| refresh | {"refresh":"..."} | On Redis PubSub | Signal to re-fetch |

### Implementation

- **LiveStatsStreamService**: SseEmitter with 1-hour timeout, CopyOnWriteArrayList for thread-safe emitter management
- **Scheduled push** every 10s (@Scheduled(fixedRate=10000))
- **Zero-wait optimization**: skips Redis lookup when no SSE clients are connected
- **Dead emitter cleanup** on completion, timeout, or error
- **Immediate initial snapshot** on subscribe to avoid blank UI

### PubSub Bridge

- **RedisAlertStreamService**: Configures RedisMessageListenerContainer on LIVE_STATS channel
- **RedisDashboardRefreshListener.onMessage()**: Decodes JSON, broadcasts to SSE
- On "stats" refresh: re-reads live stats from Redis and pushes to all SSE clients
- Always broadcasts refresh target name for UI cache invalidation

---

## Gemini AI Explanations

**Service:** AnomalyExplanationService (750 lines)

### Flow

```
GET /anomalies/{id}/explain?refresh=false
  |
  +-- 1. Fetch AnomalyEvent from SQL
  +-- 2. Gather context:
  |      SessionAnalysis (DB) + SessionInsight (Redis)
  |      + UserRiskProfile + NextActionPrediction
  |      + ActiveAnomaly + LiveStats + TrendStats
  +-- 3. Check pre-computed SQL explanation (explainabilityText)
  +-- 4. Check Redis cache (24h TTL)
  +-- 5. Build prompt -> Call Gemini API (10s timeout, 2048 tokens)
  |      Fallback: buildFallbackExplanation() -> heuristic
  +-- 6. Cache result in Redis (24h TTL)
  +-- 7. Return AnomalyExplanationDto
```

### Prompt

Constructed from 7 context sources, formatted as JSON with max 400 chars per section, requiring sections: assessment, evidence, operational impact, recommended action.

### Requirements

- GEMINI_API_KEY environment variable (optional -- falls back to heuristics when absent)
- Model: gemini-2.5-flash (configurable)
- Base URL: https://generativelanguage.googleapis.com
- Async execution via @Async + CompletableFuture
- WebClient with Reactor Netty and 10s timeout

### Caching

- Two Redis keys per anomaly: ai:explanation:anomaly:{id} (DTO) and ai:explanation:anomaly:raw:{id} (raw AI response)
- 24h TTL
- ?refresh=true query parameter bypasses cache

---

## Configuration Reference

### application.yaml Key Sections

| Prefix | Key | Default | Description |
|--------|-----|---------|-------------|
| spring.datasource | url | jdbc:sqlserver://... | SQL Server JDBC |
| | username | app_user | DB user |
| | password | securePass123! | DB password |
| spring.data.redis | host | localhost | Redis host |
| | port | 6379 | Redis port |
| spring.ai.google.genai | api-key | (empty) | Gemini API key |
| | base-url | https://generativelanguage.googleapis.com | Gemini endpoint |
| | model | gemini-2.5-flash | Gemini model name |
| app.cors | allowed-origins | http://localhost:9008 | CORS origins (comma-separated) |
| app.redis.pubsub | live-stats-channel | LIVE_STATS | PubSub channel for live stats |
| app.kafka.topics | anomaly-alerts | topic-anomaly-alerts | Kafka topic for alerts |
| server | port | 8081 | HTTP listener port |
| logging.logstash | destination | localhost:5000 | Logstash TCP endpoint |

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| SPRING_APPLICATION_NAME | api-service | App name for logs/metrics |
| SERVER_PORT | 8081 | HTTP port |
| SPRING_DATASOURCE_URL | SQL Server localhost | Database JDBC URL |
| SPRING_DATASOURCE_USERNAME | app_user | Database user |
| SPRING_DATASOURCE_PASSWORD | securePass123! | Database password |
| SPRING_DATA_REDIS_HOST | localhost | Redis host |
| SPRING_DATA_REDIS_PORT | 6379 | Redis port |
| APP_CORS_ALLOWED_ORIGINS | http://localhost:9008 | CORS allowed origins |
| REDIS_LIVE_STATS_CHANNEL | LIVE_STATS | Redis PubSub channel |
| APP_KAFKA_TOPIC_ANOMALY_ALERTS | topic-anomaly-alerts | Kafka alert topic |
| GEMINI_API_KEY | (empty) | Gemini API key (optional) |
| GEMINI_BASE_URL | https://generativelanguage.googleapis.com | Gemini base URL |
| GEMINI_MODEL | gemini-2.5-flash | Gemini model |
| SPRING_LIQUIBASE_ENABLED | false | Liquibase activation |
| LOGSTASH_DESTINATION | localhost:5000 | Logstash endpoint |

---

## Database Schema

Shared with the Data Processor. The API Service only **reads** from these tables.

### session_analysis

Full session analysis with all ML outputs. 64+ columns including:

| Column Group | Fields |
|-------------|--------|
| Identity | id, session_id, insured_id, persona, country_code, city |
| Timing | start_time, end_time, session_duration_seconds, avg/min/max_inter_action_seconds |
| Counts | total_events, unique_actions, unique_routes, unique_ips/devices, total_kos/oks |
| Flags | has_login, has_logout, ip_changed, device_changed, ended_abruptly |
| ML Scores | iso_score, anomaly_score, anomaly_probability, churn_probability, ensemble_risk_score |
| Classification | is_anomaly, anomaly_type, type_confidence, persona_cluster, risk_level |
| Sequences | action_sequence_json, route_sequence_json, action_counts_json, next_actions_json |
| Explainability | feature_contributions_json, explainability_text |

### anomaly_events

Durable anomaly alert records. 30+ columns:

| Column Group | Fields |
|-------------|--------|
| Identity | id, insured_id, session_id, event_id |
| Timing | event_time, detected_at |
| Classification | anomaly_tier, anomaly_type, anomaly_flag |
| Scores | anomaly_score, anomaly_probability, type_confidence, risk_score, churn_probability |
| Model Info | rule_type, model_artifact, persona_cluster |
| Markov | path_deviation, transition_probability, transition_from/to_action |
| Context | next_actions_json, event_json |

### user_risk_profile

Rolling risk assessment per user:

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT | PK |
| insured_id | VARCHAR | User identifier |
| last_updated | DATETIME2 | Last refresh |
| anomaly_count_7d/30d | INT | Anomaly counts |
| last_anomaly_type | VARCHAR | Most recent type |
| risk_tier | VARCHAR | HIGH/MEDIUM/LOW |
| anomaly_rate_30d | FLOAT | Anomaly ratio |
| sessions_7d/30d | INT | Session counts |
| most_frequent_action_30d | VARCHAR | Dominant action |
| avg_session_duration_30d | FLOAT | Mean duration |
| consecutive_clean_sessions | INT | Clean streak |

### next_action_prediction

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT | PK |
| insured_id | VARCHAR | User identifier |
| session_id | VARCHAR | Current session |
| predicted_at | DATETIME2 | Prediction timestamp |
| top3_actions_json | NVARCHAR(MAX) | Top-3 Markov predictions |

---

## Development Setup

### Prerequisites

- **Java 25** (JDK 25+)
- **Maven 3.9+** (wrapped via mvnw)
- **Docker** (for SQL Server + Redis)
- **SQL Server** (or Docker container)
- **Redis** (or Docker container)

### Environment Variables

Copy .env.example to .env:

```properties
SPRING_DATASOURCE_URL=jdbc:sqlserver://localhost:1433;databaseName=NoveoCareDB;encrypt=true;trustServerCertificate=true
SPRING_DATASOURCE_USERNAME=app_user
SPRING_DATASOURCE_PASSWORD=change-me
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
APP_CORS_ALLOWED_ORIGINS=http://localhost:9008
REDIS_LIVE_STATS_CHANNEL=LIVE_STATS
SERVER_PORT=8081
GEMINI_API_KEY=
LOGSTASH_DESTINATION=localhost:5000
```

### Infrastructure

```bash
# Start Vault (optional, for secret management)
docker compose -f docker-compose.vault.yaml up -d
```

SQL Server and Redis should be running externally (shared with Data Processor).

### Build & Run

```bash
# Compile
./mvnw compile

# Run tests
./mvnw test

# Run application
./mvnw spring-boot:run
```

### Verify

```bash
# Health check
curl http://localhost:8081/api/v1/health

# Live stats
curl http://localhost:8081/api/v1/stats/live

# SSE stream (connect and watch)
curl -N http://localhost:8081/api/v1/stream/live
```

---

## Testing

| Test Class | Type | Framework |
|------------|------|-----------|
| Unit tests in src/test/java/ | Unit | JUnit 5 + Instancio |

```bash
# Run all tests
./mvnw test

# Run specific test
./mvnw test -Dtest=AnalyticsControllerTest

# Skip tests
./mvnw package -DskipTests
```

Testing approach:
- **Instancio** for randomized test data generation
- **Testcontainers** for MSSQL Server integration tests
- Standard Spring Boot test slices for controller/service isolation

---

## Docker Build

Uses **Jib Maven Plugin** (no Dockerfile needed):

```bash
# Build Docker image
./mvnw compile jib:dockerBuild

# Image: api-service:0.0.1-SNAPSHOT
# Base: eclipse-temurin:25-jre
# Port: 8081
# JVM: default (UseContainerSupport)
```

---

## Project Structure

```
src/main/java/com/neo/dashboard/
+-- ApiServiceApplication.java          # Entry point
+-- config/
|   +-- CorsConfig.java                 # CORS filter
|   +-- JacksonConfig.java              # ObjectMapper + JSR310
+-- controller/
|   +-- AnalyticsController.java        # All REST endpoints (292 lines)
+-- dto/                                # 18 DTO files
+-- entity/                             # 4 JPA entities
+-- mapper/                             # 7 MapStruct mappers + JsonParsingSupport
+-- redis/
|   +-- CacheKeys.java                  # Shared Redis key definitions
+-- repository/                         # 4 Spring Data JPA repositories
+-- service/                            # 20 service classes
    +-- StatsService.java               # Redis-first stats resolution
    +-- StatsSummaryService.java        # Aggregated summary counts
    +-- CommandCenterService.java       # Dashboard aggregation
    +-- DashboardReadService.java       # Dashboard snapshot reads
    +-- LiveStatsStreamService.java     # SSE streaming
    +-- RedisDashboardRefreshListener.java  # PubSub -> SSE bridge
    +-- RedisAlertStreamService.java    # PubSub listener config
    +-- AnomalyExplanationService.java  # Gemini AI explanations (750 lines)
    +-- AnomalyInvestigationService.java# Deep-dive aggregations
    +-- ActiveAnomalyService.java       # Active anomaly from Redis
    +-- ActiveSessionService.java       # Active sessions from Redis
    +-- SessionInsightReadService.java  # Session insight from Redis
    +-- RiskProfileService.java         # Risk profile from Redis
    +-- NextActionPredictionService.java# Next actions from Redis
    +-- UserDashboardService.java       # Per-user dashboard
    +-- SessionAnalysisService.java     # Session DB access
    +-- AnomalyEventService.java        # Anomaly DB access
    +-- [RepositoryHelpers]             # 3 helper classes

src/main/resources/
+-- application.yaml                    # Main config (99 lines)
+-- db/changelog/db.changelog-master.yaml  # No-op baseline
```

---

## Key Java Files

| File | Lines | Purpose |
|------|-------|---------|
| AnalyticsController.java | 292 | All 20 REST endpoints |
| AnomalyExplanationService.java | 750 | Gemini AI explanation with multi-source context |
| LiveStatsStreamService.java | 110 | SSE streaming with PubSub bridge |
| StatsService.java | 132 | Redis-first stats resolution with dual-key fallback |
| CommandCenterService.java | 38 | Dashboard aggregation orchestrator |
| DashboardReadService.java | 88 | Typed dashboard snapshot reader |
| JsonParsingSupport.java | 174 | JSON column parsing utility |
| CacheKeys.java | 92 | Shared Redis key layout |
| CorsConfig.java | 44 | CORS configuration |
| JacksonConfig.java | 23 | JSR310 + ISO-8601 ObjectMapper |

---

## Performance Considerations

1. **Redis-first**: The service always reads from Redis for real-time data, only falling back to SQL for historical queries
2. **SSE zero-wait**: Skips Redis lookup when no SSE clients are connected
3. **Connection pooling**: HikariCP with configurable pool (default 20 max, 5 min idle)
4. **Redis pooling**: Lettuce connection pool (16 max active, 8 max idle, 2 min idle)
5. **Health check**: Separate Redis and DB health probes with degraded status reporting
6. **Liquibase disabled**: Schema migrations are owned by the Data Processor

---

## Known Limitations

1. **Read-only API service**: Schema migrations are handled by the Data Processor. Liquibase is disabled by default.
2. **No direct Kafka consumer**: The Kafka dependency exists in pom.xml but is not wired into any consumer. Anomaly alerts flow through Redis, not Kafka.
3. **Gemini API key optional**: When GEMINI_API_KEY is empty, all explanation requests fall back to heuristic (deterministic) explanations.
4. **No web application type conflict**: WebMVC starter is commented out; the service uses WebFlux (for WebClient + SSE).
