# api-service

**Read-side analytics API** for the NoveoCare fraud/security detection platform.  
Serves pre-computed risk snapshots, alert investigations, LLM-powered explanations, user 360 profiles, and dashboard views to the Quasar frontend — it never runs ML inference, never owns database migrations, and never executes destructive actions.

---

## Architecture

```
dataprocessor (writes Redis + SQL)
       |
       v
  api-service (read-side only)
       |
       v
  Quasar frontend (REST + SSE)
```

- **Read-side only**: All data is pre-computed by `dataprocessor` and written to Redis + SQL Server. `api-service` reads, aggregates, and serves.
- **Redis first, SQL fallback**: Every endpoint tries Redis for fresh snapshots, falls back to SQL `dashboard_snapshots` or entity tables, then to generated defaults.
- **No ML inference**: Risk scores, churn probabilities, next-event predictions are computed by `dataprocessor`.
- **No DB migrations**: Schema ownership belongs to `dataprocessor`; Liquibase is disabled by default.
- **Multi-source data provenance**: Every response includes a `source` field (`redis`, `sql_fallback`, `generated_fallback`, etc.) so the frontend can render degraded/fallback banners.

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Java 25 |
| Framework | Spring Boot 4.0.3 (WebFlux + MVC) |
| Database | SQL Server (via JPA/Hibernate, read-only) |
| Cache | Redis (Lettuce client, pub/sub + key/value) |
| LLM | NVIDIA NIM (Nemotron) / Gemini (optional) |
| ASR | NVIDIA Riva (gRPC) / NVIDIA NIM |
| Auth | JWT (jjwt), Spring Security |
| Build | Maven (wrapper), Jib (Docker) |
| Testing | JUnit 5, Instancio, Testcontainers |
| Observability | Actuator, Prometheus, Logstash |

---

## Project Structure

```
api-service/
├── src/main/java/com/neo/dashboard/
│   ├── ApiServiceApplication.java        # Spring Boot entry point
│   ├── asr/                              # Automatic Speech Recognition
│   │   ├── audio/AudioConverter.java     # FFmpeg WAV conversion
│   │   ├── config/AsrProperties.java     # ASR configuration binding
│   │   ├── controller/AsrController.java # REST transcription endpoints
│   │   ├── dto/                          # ASR DTOs
│   │   ├── riva/proto/                   # Generated protobuf stubs (gRPC)
│   │   └── service/                      # Riva gRPC + NIM REST clients
│   ├── assistant/                        # Dashboard AI Assistant
│   │   ├── DashboardAssistantService.java       # Core NL→command pipeline
│   │   ├── DashboardAssistantCapabilityRegistry.java  # Route/element allowlist
│   │   ├── DashboardAssistantController.java    # REST endpoints
│   │   ├── DashboardAssistantManifest.java      # Manifest loader
│   │   └── ...                           # DTOs, command model
│   ├── config/                           # CORS, Jackson, LLM, Security, PasswordEncoder
│   ├── controller/                       # REST controllers (v1)
│   │   ├── AccountController.java
│   │   ├── AiRuntimeController.java
│   │   ├── AlertController.java
│   │   ├── AuthController.java
│   │   ├── ChurnController.java
│   │   ├── ExplanationController.java
│   │   ├── ForecastController.java
│   │   ├── HealthController.java
│   │   ├── NextEventPredictionController.java
│   │   ├── SecurityDashboardController.java
│   │   ├── StreamController.java         # SSE live stream
│   │   └── User360Controller.java
│   ├── dto/                              # Shared response DTOs
│   │   ├── ApiResponse.java              # Standard envelope (data + meta)
│   │   ├── AuthResponse.java, SignInRequest.java, SignUpRequest.java, ...
│   │   └── v36/                          # V3.6.1 endpoint DTOs (25 files)
│   ├── entity/                           # JPA entities (read-only)
│   │   ├── User.java
│   │   ├── AnomalyEvent.java
│   │   ├── SessionAnalysis.java
│   │   ├── LlmExplanation.java
│   │   ├── NextEventPrediction.java
│   │   ├── DashboardSnapshot.java
│   │   └── UserRole.java                 # (enum)
│   ├── exception/                        # Global handler + custom exceptions
│   ├── mapper/                           # MapStruct mappers
│   ├── redis/                            # Redis cache key constants
│   ├── repository/                       # Spring Data JPA repositories
│   ├── security/                         # JWT token provider, filter, hash util
│   └── service/                          # Business logic
│       ├── AuthService.java
│       ├── EmailVerificationService.java
│       ├── PasswordResetService.java
│       ├── LlmExplanationService.java    # LLM explanation generation + caching
│       ├── LlmExplanationCacheService.java
│       ├── LlmEvidenceReadService.java
│       ├── LlmPromptBuilderV36.java      # Prompt construction for NVIDIA NIM
│       ├── V36AlertService.java          # Alert read pipeline (ZSET → SQL → hydrate)
│       ├── V36DashboardService.java      # Security overview, diagnostics
│       ├── V36User360Service.java        # User 360 profile
│       ├── V36NextEventPredictionService.java
│       ├── V36RedisReadService.java      # Redis read strategy
│       ├── LiveStatsStreamService.java   # SSE push
│       ├── StatsService.java
│       ├── DashboardSnapshotFallbackService.java
│       └── llm/
│           ├── LlmProvider.java          # Interface
│           ├── NvidiaNimLlmProvider.java # NVIDIA NIM HTTP client
│           └── GeminiLlmProvider.java    # Gemini client
├── src/main/resources/
│   ├── application.yaml                  # All configuration (200+ properties)
│   ├── dashboard-assistant-manifest.json # Full route/element/panel registry
│   ├── AI/reports/                       # Static AI use-case winners
│   └── templates/email/                  # Auth code email templates
├── src/test/java/                        # Comprehensive test suite
├── docs/
│   ├── api-service-v36-endpoints.md      # Full endpoint reference (1163 lines)
│   ├── api-service-dashboard-assistant.md # Assistant architecture & commands
│   └── dashboard-assistant-ui-registry.md # Frontend/backend ID consistency
├── pom.xml                               # Maven build (Spring Boot 4.0.3, Java 25)
└── .env.example                          # Environment variable template
```

---

## Getting Started

### Prerequisites

- **Java 25** (JDK 25+)
- **SQL Server** instance (or Docker)
- **Redis** 6+ (or Docker)
- **NVIDIA API key** for LLM explanations and ASR (or Gemini key as fallback)
- **FFmpeg** (optional, for ASR audio conversion)

### Setup

```bash
# 1. Clone and enter the project
cd api-service

# 2. Copy environment template and fill in your values
cp .env.example .env

# 3. Build (tests excluded for quick setup)
./mvnw clean compile -DskipTests

# 4. Run
./mvnw spring-boot:run
```

The service starts on port `8081` by default.

### Required Configuration

| Env Variable | Description |
|---|---|
| `SPRING_DATASOURCE_USERNAME` | SQL Server username |
| `SPRING_DATASOURCE_PASSWORD` | SQL Server password |
| `NVIDIA_API_KEY` | NVIDIA NIM API key (for LLM + ASR) |
| `JWT_SECRET` | 64+ character random secret for JWT signing |

---

## API Endpoints

### Auth & Account

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/signup` | Register |
| POST | `/api/v1/auth/signin` | Login |
| POST | `/api/v1/auth/refresh` | Refresh token |
| POST | `/api/v1/auth/verify-email` | Verify email code |
| POST | `/api/v1/auth/resend-verification` | Resend verification code |
| POST | `/api/v1/auth/forgot-password` | Request password reset |
| POST | `/api/v1/auth/password-reset/verify` | Verify reset token |
| POST | `/api/v1/auth/password-reset/confirm` | Confirm password reset |
| POST | `/api/v1/auth/change-password` | Change password (authenticated) |

### Health

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/health` | Redis + DB health check |

### V3.6.1 Analytics Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/alerts/live` | Live alerts (paginated, filterable) |
| GET | `/api/v1/alerts/critical` | Shortcut for `?riskLevel=CRITICAL` |
| GET | `/api/v1/alerts/{eventId}` | Alert investigation detail |
| GET | `/api/v1/users/{insuredId}/360` | User 360 profile |
| GET | `/api/v1/users/{insuredId}/alerts` | User-scoped alert history |
| GET | `/api/v1/security/overview` | Security overview dashboard |
| GET | `/api/v1/security/diagnostics` | System diagnostics + field coverage |
| GET | `/api/v1/churn/dashboard` | Churn risk summary |
| GET | `/api/v1/churn/users` | Churn risk user list |
| GET | `/api/v1/forecast/dashboard` | Forecast dashboard |
| GET | `/api/v1/ai/runtime-health` | Runtime health (Kafka, models, performance) |
| GET | `/api/v1/users/{insuredId}/next-event-prediction` | Next-event prediction by user |
| GET | `/api/v1/sessions/{sessionId}/next-event-prediction` | Next-event prediction by session |
| GET | `/api/v1/ai/final-winners` | AI use-case winners report |
| GET | `/api/v1/ai/reports` | Reports metadata |

### LLM Explanations

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/explanations/alerts/{eventId}/evidence` | LLM evidence payload |
| GET | `/api/v1/explanations/alerts/{eventId}` | Cached explanation (no LLM call) |
| POST | `/api/v1/explanations/alerts/{eventId}` | Generate/refresh explanation |

### Dashboard Assistant

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/dashboard-assistant/message` | Process natural language command |
| GET | `/api/v1/dashboard-assistant/capabilities` | Return route/element/panel allowlist |

### ASR (Speech Recognition)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/asr/transcribe` | Transcribe audio (NVIDIA Riva/NIM) |
| GET | `/api/v1/asr/status` | ASR service health |

### SSE Stream

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/stream/live` | SSE event stream (stats, alerts, dashboard refreshes) |

### Legacy

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/anomalies/{id}/explain` | Legacy explanation (V3.6.1 fallback) |

### Data Provenance (source field)

All V3.6.1 endpoints return a `source` field indicating data origin:

| Source | Meaning |
|--------|---------|
| `redis` | Fresh Redis snapshot |
| `sql_fallback` | SQL `dashboard_snapshots` or entity rows |
| `sql` | Direct SQL entity query |
| `sql-payload` | Deserialized from investigation JSON stored in SQL |
| `generated_fallback` | Computed defaults (Redis + SQL both missed) |
| `generated` | Freshly generated by LLM provider |
| `force_generated` | Generated with `forceRefresh=true` |
| `provider_error_fallback` | LLM returned error, local heuristic used |

---

## Key Features

### Alert Investigation Pipeline
Four-tier read strategy: Redis investigation payload → Redis live payload → SQL investigation JSON → safe SQL-only mode. Evidence hydration pulls from LLM evidence payloads when detail fields are missing. Defensive `eventId` matching prevents session-scoped payloads from leaking into unrelated events.

### LLM Explanation System
- Powered by **NVIDIA NIM** (default) or **Gemini** (optional)
- Two-message structured prompt: system role definition + normalized evidence + output schema
- Multi-level caching: Redis (`:*evidenceHash*:style:language` + `:latest`), SQL `llm_explanations`
- Concurrency lock prevents duplicate generation (409 `GENERATION_IN_PROGRESS`)
- `forceRefresh=true` bypasses all caches, regenerates, replaces both Redis and SQL
- Provider errors return heuristic fallback without poisoning the cache

### Dashboard AI Assistant
- Translates natural language into validated command DTOs for the frontend
- Three-stage pipeline: deterministic pattern match (fast path) → model call → final deterministic fallback
- Command allowlisting: all types, routes, element IDs, and panel IDs validated against `DashboardAssistantManifest`
- Multiple model fallback chain with configurable candidate models
- Executes no destructive actions — read/navigate/search/highlight/filter/refresh only

### Live Alerts SSE
- Redis pub/sub `LIVE_STATS` channel broadcasts stats and dashboard refresh events
- Frontend receives lightweight invalidation payloads: `{"refresh": "alerts"}`
- Existing event types: `stats`, `refresh` plus V3.6.1: `alerts`, `security-overview`, `runtime-health`, `churn`, `forecast`

### ASR (Speech Recognition)
- **NVIDIA Riva** via gRPC (streaming) or **NVIDIA NIM** via REST
- Automatic audio conversion via FFmpeg (any format → 16kHz mono WAV)
- Configurable language, model, timeout

---

## Configuration

All configuration is in `application.yaml` and overridable via environment variables (loaded from `.env`).

**LLM & Explanations** (`app.llm.*`):
- `provider`: `nvidia-nim` or `gemini`
- `nvidia.*`: model, temperature, top-p, max-tokens, timeout, reasoning budget, request logging
- `cache.*`: Redis TTL, generation lock TTL

**Dashboard Assistant** (`app.assistant.dashboard.*`):
- `enabled`, `model`, `temperature`, `max-tokens`, `timeout-ms`
- `planner-mode`: `model-only` or `deterministic-first`
- `candidate-models`: ordered fallback chain
- `use-guided-json`: enable structured output constraints (model-dependent)
- Per-error-type fallback toggles (timeout, network, 5xx, parse failure)

**ASR** (`app.asr.*`):
- `provider`: `nvidia-riva` or `nvidia-nim`
- `protocol`: `grpc` or `rest`
- Audio conversion parameters (sample rate, channels, max size)

**V3.6.1** (`app.v36.*`):
- Redis key preference (`v36` vs legacy), fallback behavior
- Explanation cache TTL, evidence size limit, generation lock
- Endpoint visibility toggles (reports, diagnostics)

---

## Building & Running

```bash
# Compile (excludes tests)
./mvnw compile

# Run tests
./mvnw test

# Package
./mvnw package -DskipTests

# Run
./mvnw spring-boot:run

# Docker image (Jib)
./mvnw compile jib:dockerBuild

# Watch mode (auto-restart on source changes)
./mvnw spring-boot:run -Dspring-boot.run.fork=true
# Then in another terminal:
./mvnw compile
```

---

## Development

### Code Style
- Java 25, Spring Boot 4.0.3, WebFlux + MVC
- Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`)
- MapStruct for DTO mapping
- All endpoints use `ApiResponse<T>` envelope
- V3.6.1 endpoints return `schemaVersion: "v3.6.1"` in data payloads

### Testing
- JUnit 5 + Instancio for randomized test data
- Testcontainers for Redis/SQL Server integration tests
- WireMock-style assertions via MockWebServer for HTTP provider tests
- Comprehensive service-layer tests with mocked repositories

### Conventions
- Every endpoint has Redis-first, SQL-fallback, generated-fallback strategy
- Source/warning metadata at every level for frontend degraded-mode banners
- Null-safe responses: absent fields are omitted, not returned as `null`
- Evidence payloads are never truncated silently — `max-evidence-size-kb` controls rejection
- All assistant commands are validated against the allowlist before returning to the frontend

---

## Related Projects

- **dataprocessor**: ML inference engine — writes Redis snapshots and SQL rows that `api-service` reads
- **Quasar frontend**: Vue 3/Quasar SPA consuming these endpoints
- **Vault**: Sidecar secrets management (`docker-compose.vault.yaml`)
