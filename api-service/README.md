# API Service — NoveoCare Analytics REST API

A **Spring Boot 4.0.3** read-side REST API that serves real-time analytics, session data, anomaly events, risk profiles, and AI-powered explanations to the Quasar frontend dashboard. Reads from Redis (written by the **Data Processor** worker) and SQL Server, with optional Gemini AI integration for natural-language anomaly explanations.

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

`
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
`

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
| Risk profile | isk:{insuredId} per user update | RiskProfileService.getRiskProfile() |
| Next actions | 
ext_actions:{insuredId} per session | NextActionPredictionService.getPrediction() |
| Active anomaly | nomaly:active:{insuredId} per anomaly | ActiveAnomalyService.getActiveAnomaly() |
| Dashboard snapshots | dashboard:{view} per refresh cycle | DashboardReadService.getSnapshot() |

### Redis PubSub Bridge

The Data Processor publishes refresh notifications to the LIVE_STATS Redis PubSub channel. The API Service's RedisDashboardRefreshListener picks up these notifications and broadcasts them to all SSE subscribers.

`
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
`

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
| GET | /sessions | List sessions (filters: insuredId, rom/	o, isAnomaly, signature) | Yes |
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
| GET | /anomalies | List anomaly events (filters: insuredId, rom/	o, 	ier, 	ype) | Yes |
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
| GET | /dashboard/{view} | Single snapshot. Valid views: lerts, isky-sessions, cluster-mix, drop-offs, path-deviations, orecasts, orecast-series |

### Streaming & Health

| Method | Path | Description |
|--------|------|-------------|
| GET | /stream/live | SSE stream (live stats every 10s) |
| GET | /health | Health check (Redis + DB, returns UP/DEGRADED) |

---

## SSE Live Streaming

**Endpoint:** GET /api/v1/stream/live produces 	ext/event-stream

### Events

| Name | Payload | Frequency | Description |
|------|---------|-----------|-------------|
| stats | StatsApiResponseDto | Every 10s + immediate on subscribe | Live stats snapshot |
| efresh | {"refresh":"..."} | On Redis PubSub | Signal to re-fetch |

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

`
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
`

### Prompt

Constructed from 7 context sources, formatted as JSON with max 400 chars per section, requiring sections: ssessment, evidence, operational impact, ecommended action.

### Requirements

- GEMINI_API_KEY environment variable (optional -- falls back to heuristics when absent)
- Model: gemini-2.5-flash (configurable)
- Base URL: https://generativelanguage.googleapis.com
- Async execution via @Async + CompletableFuture
- WebClient with Reactor Netty and 10s timeout

### Caching

- Two Redis keys per anomaly: i:explanation:anomaly:{id} (DTO) and i:explanation:anomaly:raw:{id} (raw AI response)
- 24h TTL
- ?refresh=true query parameter bypasses cache

---

## DTO Layer

18 DTO files in com.neo.dashboard.dto:

| DTO | Fields | Purpose |
|-----|--------|---------|
| ActiveSessionDto | sessionId, insuredId, lastEvent, actionCount, duration, anomalyFlag, riskScore, etc. | Live session from Redis insight |
| AnomalyAlertDto | insuredId, sessionId, type, tier, score, probability, riskScore, ruleType, modelArtifact, nextActions, eventContext | Active anomaly for a user |
| AnomalyEventDto | id, insuredId, sessionId, anomalyType, tier, flag, score, probability, confidence, riskScore, cluster, pathDeviation, nextActions | Anomaly event from DB |
| AnomalyExplanationDto | anomalyEventId, source, modelVersion, generatedAt, cached, explanation | AI or heuristic explanation |
| AnomalyInvestigationDto | anomaly, session, relatedAnomalies, timeline | Deep-dive aggregation |
| ApiResponse<T> | data, meta (PaginationMeta), timestamp | Standard API envelope |
| CommandCenterDto | liveStats, trendForecast, forecastDetails, alerts, riskySessions, clusterMix, dropOffs, pathDeviations | Aggregated dashboard |
| FeatureContributionDto | feature, importance, actualValue, description | ML feature importance |
| NextActionPredictionDto | insuredId, sessionId, predictedAt, top3Actions | Markov next-action predictions |
| NextActionScoreDto | action, probability | Single action+probability pair |
| PaginationMeta | page, size, totalElements, totalPages | Pagination metadata |
| PathDeviationDto | deviated, fromAction, toAction, transitionProbability | Markov deviation result |
| SessionAnalysisDto | id, sessionId, insuredId, persona, country, times, scores, features, sequences, anomalies, risk | Full session analysis |
| StatsApiResponseDto | date, source, payload | Stats/trend response wrapper |
| StatsResponseDto | date (LocalDate), source (String), payload (JsonNode) | Internal stats DTO |
| StatsSummaryDto | totalSessions, totalAnomalies, activeSessionsNow, eventsToday, anomalyRate, breakdowns | Aggregated counts |
| UserDashboardDto | riskProfile, recentSessions, recentAnomalies, nextActions, clusterHistory | Per-user dashboard |
| UserRiskProfileDto | insuredId, tier, counts, rates, actions, duration, consecutiveClean | User risk profile |

---

## Mapper Layer

Uses **MapStruct** for entity-to-DTO conversion (annotation processor with componentModel=spring).

| Mapper | Source | Target | Custom Logic |
|--------|--------|--------|-------------|
| SessionAnalysisMapper | SessionAnalysis | SessionAnalysisDto | Standard MapStruct |
| AnomalyEventMapper | AnomalyEvent | AnomalyEventDto | Standard MapStruct |
| AnomalyAlertMapper | AnomalyAlertDto + AnomalyEvent | Persistence mapping | Custom detectedAtOrNow |
| UserRiskProfileMapper | UserRiskProfile | UserRiskProfileDto | Standard MapStruct |
| NextActionPredictionMapper | NextActionPrediction | NextActionPredictionDto | Standard MapStruct |
| EntityMapper | Marker interface for generic DTO/entity conversion | -- | -- |
| JsonParsingSupport | Utility for JSON string <-> object conversion | -- | 174 lines of parsing helpers (safe conversions, fallback handling) |

The JsonParsingSupport is a shared utility that safely parses JSON column values (stored as NVARCHAR(MAX) in SQL) into Java objects with graceful fallback for null, blank, or malformed values. Used by all mappers when converting *_json entity fields to their DTO equivalents.

---

## Redis Key Layout

Keys shared with the **Data Processor** worker. Defined in com.neo.dashboard.redis.CacheKeys.

### Live Stats & Trends

| Key Pattern | TTL | Description |
|-------------|-----|-------------|
| stats:live:{date} | 24h | Live real-time stats snapshot |
| stats:trend:{date} | 24h | Trend/forecast snapshot (legacy) |
| stats:events:day:{date} | 24h | Daily event counter |
| stats:alerts:day:{date} | 24h | Daily alert counter |
| stats:downloads:day:{date} | 24h | Daily download counter |

### Dashboard Snapshots

| Key Pattern | TTL | Description |
|-------------|-----|-------------|
| dashboard:alerts | 15m | Recent alerts feed |
| dashboard:risky-sessions | 15m | Top risky sessions |
| dashboard:cluster-mix | 15m | Persona cluster distribution |
| dashboard:drop-offs | 15m | Drop-off action counts |
| dashboard:path-deviations | 15m | Path deviation events |
| dashboard:forecasts | 15m | Full forecast snapshot |
| dashboard:forecast-series | 15m | Forecast time series |

### Cached Entities

| Key Pattern | TTL | Description |
|-------------|-----|-------------|
| session:insight:{insuredId}:{sessionId} | 2h | Live session insight payload |
| session:insight:index | -- (set) | Active insight key index |
| session:insight:index:{insuredId} | -- (set) | Per-user insight index |
| isk:{insuredId} | 15m | User risk profile |
| 
ext_actions:{insuredId} | 2h | Top-3 next actions |
| nomaly:active:{insuredId} | 30m | Active anomaly flag |
| pending:alerts:{sessionId} | 30m | Deduplication guard |

### AI Explanations

| Key Pattern | TTL | Description |
|-------------|-----|-------------|
| i:explanation:anomaly:{id} | 24h | Cached anomaly explanation DTO |
| i:explanation:anomaly:raw:{id} | 24h | Raw Gemini API response |

---

## Configuration Reference

### pplication.yaml Key Sections

| Prefix | Key | Default | Description |
|--------|-----|---------|-------------|
| spring.datasource | url | jdbc:sqlserver://... | SQL Server JDBC |
| | username | pp_user | DB user |
| | password | securePass123! | DB password |
| spring.data.redis | host | localhost | Redis host |
| | port | 6379 | Redis port |
| spring.ai.google.genai | pi-key | (empty) | Gemini API key |
| | ase-url | https://generativelanguage.googleapis.com | Gemini endpoint |
| | model | gemini-2.5-flash | Gemini model name |
| pp.cors | llowed-origins | http://localhost:9008 | CORS origins (comma-separated) |
| pp.redis.pubsub | live-stats-channel | LIVE_STATS | PubSub channel for live stats |
| pp.kafka.topics | nomaly-alerts | 	opic-anomaly-alerts | Kafka topic for alerts |
| server | port | 8081 | HTTP listener port |
| logging.logstash | destination | localhost:5000 | Logstash TCP endpoint |

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| SPRING_APPLICATION_NAME | pi-service | App name for logs/metrics |
| SERVER_PORT | 8081 | HTTP port |
| SPRING_DATASOURCE_URL | SQL Server localhost | Database JDBC URL |
| SPRING_DATASOURCE_USERNAME | pp_user | Database user |
| SPRING_DATASOURCE_PASSWORD | securePass123! | Database password |
| SPRING_DATA_REDIS_HOST | localhost | Redis host |
| SPRING_DATA_REDIS_PORT | 6379 | Redis port |
| APP_CORS_ALLOWED_ORIGINS | http://localhost:9008 | CORS allowed origins |
| REDIS_LIVE_STATS_CHANNEL | LIVE_STATS | Redis PubSub channel |
| APP_KAFKA_TOPIC_ANOMALY_ALERTS | 	opic-anomaly-alerts | Kafka alert topic |
| GEMINI_API_KEY | (empty) | Gemini API key (optional) |
| GEMINI_BASE_URL | https://generativelanguage.googleapis.com | Gemini base URL |
| GEMINI_MODEL | gemini-2.5-flash | Gemini model |
| SPRING_LIQUIBASE_ENABLED | alse | Liquibase activation |
| LOGSTASH_DESTINATION | localhost:5000 | Logstash endpoint |

---

## Database Schema

Shared with the Data Processor. The API Service only **reads** from these tables.

### session_analysis

Full session analysis with all ML outputs. 64+ columns including:

| Column Group | Fields |
|-------------|--------|
| Identity | id, session_id, insured_id, persona, country_code, city |
| Timing | start_time, end_time, session_duration_seconds, vg/min/max_inter_action_seconds |
| Counts | 	otal_events, unique_actions, unique_routes, unique_ips/devices, 	otal_kos/oks |
| Flags | has_login, has_logout, ip_changed, device_changed, ended_abruptly |
| ML Scores | iso_score, nomaly_score, nomaly_probability, churn_probability, ensemble_risk_score |
| Classification | is_anomaly, nomaly_type, 	ype_confidence, persona_cluster, isk_level |
| Sequences | ction_sequence_json, oute_sequence_json, ction_counts_json, 
ext_actions_json |
| Explainability | eature_contributions_json, explainability_text |

### nomaly_events

Durable anomaly alert records. 30+ columns:

| Column Group | Fields |
|-------------|--------|
| Identity | id, insured_id, session_id, event_id |
| Timing | event_time, detected_at |
| Classification | nomaly_tier, nomaly_type, nomaly_flag |
| Scores | nomaly_score, nomaly_probability, 	ype_confidence, isk_score, churn_probability |
| Model Info | ule_type, model_artifact, persona_cluster |
| Markov | path_deviation, 	ransition_probability, 	ransition_from/to_action |
| Context | 
ext_actions_json, event_json |

### user_risk_profile

Rolling risk assessment per user:

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT | PK |
| insured_id | VARCHAR | User identifier |
| last_updated | DATETIME2 | Last refresh |
| nomaly_count_7d/30d | INT | Anomaly counts |
| last_anomaly_type | VARCHAR | Most recent type |
| isk_tier | VARCHAR | HIGH/MEDIUM/LOW |
| nomaly_rate_30d | FLOAT | Anomaly ratio |
| sessions_7d/30d | INT | Session counts |
| most_frequent_action_30d | VARCHAR | Dominant action |
| vg_session_duration_30d | FLOAT | Mean duration |
| consecutive_clean_sessions | INT | Clean streak |

### 
ext_action_prediction

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT | PK |
| insured_id | VARCHAR | User identifier |
| session_id | VARCHAR | Current session |
| predicted_at | DATETIME2 | Prediction timestamp |
| 	op3_actions_json | NVARCHAR(MAX) | Top-3 Markov predictions |

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

`properties
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
`

### Infrastructure

`ash
# Start Vault (optional, for secret management)
docker compose -f docker-compose.vault.yaml up -d
`

SQL Server and Redis should be running externally (shared with Data Processor).

### Build & Run

`ash
# Compile
./mvnw compile

# Run tests
./mvnw test

# Run application
./mvnw spring-boot:run
`

### Verify

`ash
# Health check
curl http://localhost:8081/api/v1/health

# Live stats
curl http://localhost:8081/api/v1/stats/live

# SSE stream (connect and watch)
curl -N http://localhost:8081/api/v1/stream/live
`

---

## Testing

| Test Class | Type | Framework |
|------------|------|-----------|
| Unit tests in src/test/java/ | Unit | JUnit 5 + Instancio |

`ash
# Run all tests
./mvnw test

# Run specific test
./mvnw test -Dtest=AnalyticsControllerTest

# Skip tests
./mvnw package -DskipTests
`

Testing approach:
- **Instancio** for randomized test data generation
- **Testcontainers** for MSSQL Server integration tests
- Standard Spring Boot test slices for controller/service isolation

---

## Docker Build

Uses **Jib Maven Plugin** (no Dockerfile needed):

`ash
# Build Docker image
./mvnw compile jib:dockerBuild

# Image: api-service:0.0.1-SNAPSHOT
# Base: eclipse-temurin:25-jre
# Port: 8081
# JVM: default (UseContainerSupport)
`

---

## Project Structure

`
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
    +-- StatsService.java               # Redis-firt stats resolution
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
`

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
