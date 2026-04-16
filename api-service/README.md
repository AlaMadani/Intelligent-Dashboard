# API Service

Read-oriented Spring Boot service that backs the analytics dashboard. It exposes REST endpoints and Server-Sent Events (SSE) over the same SQL Server and Redis instances populated by the **Data Processor** worker, and consumes live anomaly alerts from Kafka.

The Data Processor was refactored around a **manifest-driven ML stack** (tabular ONNX detectors, Markov transitions, session insights, and dashboard snapshots). This API mirrors those contracts: JPA entities match the current `session_analysis` / `anomaly_events` schema, Redis keys follow the shared `CacheKeys` layout, and alert DTOs align with the Kafka `AnomalyAlert` JSON shape.

## Responsibilities

- Paginated reads for session analysis and anomaly events
- Risk profile and latest next-action prediction per insured user
- Live and trend statistics (Redis first, SQL fallback for trends)
- Active anomaly marker (Redis, with SQL fallback)
- **Dashboard snapshots** from Redis keys `dashboard:{view}` (filled by the Data Processor `DashboardSnapshotService` and schedulers)
- **Live session insight** from Redis `session:insight:{insuredId}:{sessionId}` while a session is open
- SSE streams for anomaly alerts and periodic live stats
- Optional Gemini-powered explanations for individual anomaly events

## Tech stack

| Area | Technology |
| --- | --- |
| Runtime | Java 25, Spring Boot 4 |
| HTTP | Spring Web MVC |
| Data | Spring Data JPA (read-only entities), SQL Server JDBC |
| Cache | Spring Data Redis (`StringRedisTemplate`) |
| Messaging | Spring Kafka (anomaly alert consumer) |
| Mapping | MapStruct |
| JSON | Jackson (Spring Boot 4 `tools.jackson` stack) |
| Observability | Actuator, Micrometer Prometheus registry, Logstash encoder |

## Configuration

Primary settings live in `src/main/resources/application.yaml` (overridable via environment variables as documented in `.env.example`).

Important properties:

| Prefix / keys | Purpose |
| --- | --- |
| `spring.datasource.*` | SQL Server read connection |
| `spring.data.redis.*` | Redis host/port (must match the Data Processor) |
| `spring.kafka.*` | Kafka bootstrap and consumer group |
| `app.kafka.topics.anomaly-alerts` | Topic name for anomaly SSE bridge |
| `spring.ai.google.genai.*` | Optional Gemini API for explanations |
| `server.port` | Default HTTP port `8081` |

## Redis key contract (shared with Data Processor)

The worker and this API must agree on key names. Notable keys:

| Key pattern | Writer | Reader in this API |
| --- | --- | --- |
| `stats:live:{date}` | Data Processor `StatisticsService` | `GET /api/analytics/stats/live`, SSE `/stream/live` |
| `stats:trend:{date}` | Reserved / optional | `GET /api/analytics/stats/trend` (often SQL fallback) |
| `dashboard:{view}` | Data Processor `DashboardSnapshotService` / `TrendPredictionScheduler` | `GET /api/analytics/dashboard/{view}` |
| `session:insight:{insuredId}:{sessionId}` | Data Processor while session is active | `GET /api/analytics/sessions/{insuredId}/{sessionId}/insight` |
| `next_actions:{insuredId}` | Data Processor | Used when enriching streamed alerts (indirectly via DB for predictions) |
| `risk:{insuredId}` | Data Processor | `RiskProfileService` |
| `anomaly:active:{insuredId}` | Data Processor `AlertPublisher` | `GET /api/analytics/anomaly/active/{insuredId}` |

### Allowed `dashboard` views

Path parameter `view` must be one of:

- `alerts` — recent anomaly-style rows (`items`, `generatedAt`)
- `risky-sessions` — high risk / live insight rows
- `cluster-mix` — persona cluster distribution
- `drop-offs` — abrupt session ends by last action
- `path-deviations` — low-probability transitions
- `forecasts` — bundled forecast metadata and points
- `forecast-series` — periodic snapshot from `TrendPredictionScheduler` (nested series + MAE/RMSE)

Unknown views return **404**.

## HTTP API overview

Base path: `/api/analytics`

| Method | Path | Description |
| --- | --- | --- |
| GET | `/sessions` | Paginated session analysis (filters: `insuredId`, `from`, `to`, `isAnomaly`) |
| GET | `/sessions/{id}` | Session analysis by database id |
| GET | `/sessions/{insuredId}/{sessionId}/insight` | Live Redis insight JSON for an **active** session |
| GET | `/anomaly-events` | Paginated anomaly events |
| GET | `/anomaly-events/{id}` | Anomaly event by id |
| GET | `/anomaly-events/{id}/explanation` | Gemini or heuristic explanation |
| GET | `/risk/{insuredId}` | User risk profile |
| GET | `/next-actions/{insuredId}` | Latest persisted next-action prediction |
| GET | `/dashboard/{view}` | Redis dashboard snapshot (see views above) |
| GET | `/stats/live` | Live stats JSON for a date (default today) |
| GET | `/stats/trend` | Trend stats (Redis or SQL) |
| GET | `/anomaly/active/{insuredId}` | Active anomaly marker |
| GET | `/stream/anomalies` | SSE anomaly stream |
| GET | `/stream/live` | SSE live stats (10s cadence) |

Responses use the `ApiResponse<T>` envelope (`data`, optional `meta` for pagination).

## SQL schema alignment

Entities under `com.neo.dashboard.entity` map read-only views of tables produced by the Data Processor pipeline, including session-level fields such as:

- Persona, geography, route/action summaries, download/ping-pong metrics
- `iso_score`, `anomaly_probability`, `churn_probability`, `ensemble_risk_score`, `persona_cluster`
- Markov context: `path_deviation`, `transition_probability`, `transition_from_action`, `transition_to_action`
- JSON columns: `action_counts_json`, `anomaly_types_json`, `campaign_ids_json`, `top3_next_actions`

`anomaly_events` includes the extended alert metadata (`anomaly_probability`, `churn_probability`, `risk_score`, `path_deviation`, transition fields, `model_artifact`, `next_actions_json`).

Ensure Liquibase / migrations have been applied on the database **before** running this service (`spring.jpa.hibernate.ddl-auto` is `none`).

## Local development

Prerequisites: Java 25, Maven Wrapper, reachable SQL Server, Redis, and Kafka (same as the Data Processor stack).

```powershell
.\mvnw.cmd compile
.\mvnw.cmd spring-boot:run
```

Unit tests that do not load the full Spring context:

```powershell
.\mvnw.cmd test "-Dtest=SessionAnalysisServiceTest,NextActionPredictionMapperTest"
```

Full `mvn test` may require Docker for Testcontainers-based integration tests and a valid database for `@SpringBootTest`.

## Relationship to the Data Processor

| Data Processor concept | This service |
| --- | --- |
| `SessionInsight` cached in Redis | `GET .../sessions/{insuredId}/{sessionId}/insight` |
| `DashboardSnapshotService` views | `GET .../dashboard/{view}` |
| `AnomalyAlert` Kafka JSON | `AnomalyAlertDto`, SSE bridge, active anomaly cache |
| `session_analysis` rows | `SessionAnalysis` entity / DTO |
| `anomaly_events` rows | `AnomalyEvent` entity / DTO |

Deploy the Data Processor and this API against the **same** Redis, Kafka topic names, and SQL database so dashboards and streams stay consistent.
