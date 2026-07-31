# NoveoCare Fraud & Security Detection Platform

A real-time, ML-powered fraud and security detection platform that ingests insured-user audit trails, reconstructs behavioral sessions, applies a multi-tier anomaly detection ensemble (heuristic rules, tree-based models, sequence models), fuses all signals into a unified risk score, and publishes alerts and analytics to Redis, SQL Server, and Kafka — served through a read-side REST API to a Quasar/Vue 3 frontend.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [System Data Flow](#system-data-flow)
3. [Repository Structure](#repository-structure)
4. [Core Services](#core-services)
   - [Data Processor](#data-processor)
   - [API Service](#api-service)
5. [Infrastructure](#infrastructure)
   - [Kafka + Redis + Kafka Connect](#kafka--redis--kafka-connect)
   - [ELK Stack](#elk-stack)
   - [Monitoring](#monitoring)
6. [DevOps](#devops)
7. [ML Models & Scoring Strategy](#ml-models--scoring-strategy)
8. [Getting Started](#getting-started)
9. [Environment Variables](#environment-variables)
10. [CI/CD Pipeline](#cicd-pipeline)
11. [Documentation Index](#documentation-index)

---

## Architecture Overview

```
Cosmos DB (Azure)
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
                     │      ├── next_event_predictions
                     │      └── dashboard_snapshots
                     │
                     └──► Kafka topic ──► api-service ──► Quasar Frontend
                           anomaly-alerts      │              (REST + SSE)
                                               │
                                          ┌────┴────┐
                                          │ LLM     │
                                          │(NVIDIA  │
                                          │ NIM /   │
                                          │ Gemini) │
                                          └─────────┘
```

The platform follows a **write-side / read-side split** architecture:
- **Data Processor** (write-side): Ingests events, runs ML inference, writes results to Redis + SQL Server + Kafka
- **API Service** (read-side only): Reads pre-computed data from Redis (primary) or SQL Server (fallback), serves REST/SSE to the frontend, and calls LLM providers for explanation generation

All 7 Docker Compose environments share a common `shared-net` Docker network.

---

## System Data Flow

### Event Processing Pipeline

Each incoming Kafka event goes through this pipeline inside the Data Processor:

```
Kafka record
  │
  ├─ 1. Parse JSON → AuditTrailEvent
  ├─ 2. Idempotency check (Redis marker)
  ├─ 3. Session state management
  ├─ 4. Record statistics
  ├─ 5. Append to Redis session buffer
  ├─ 6. Fetch & sort session event history
  ├─ 7. Feature engineering / enrichment
  │      ├─ Time deltas (inter-action gaps)
  │      ├─ Change detection (IP, device, country)
  │      ├─ KO streak tracking
  │      ├─ Download burst detection (sliding 2min window)
  │      └─ Ping-pong loop detection
  ├─ 8. Build/update running session summary
  ├─ 9. Evaluate session-level rules
  ├─10. Model inference
  │      ├─ Tabular models: XGBoost, LightGBM, CatBoost, OneClassSVM
  │      ├─ Sequence models: Transformer or TCN
  │      ├─ Churn prediction (ExtraTrees)
  │      └─ Forecast evaluation
  ├─11. Session finalization OR alert publication
  ├─12. Cache session insight
  └─13. Acknowledge Kafka offset
```

### API Service Read Flow

Every V3.6.1 endpoint follows a **Redis-first, SQL-fallback** strategy:

1. Try Redis key for fresh snapshot
2. If miss → query SQL `dashboard_snapshots` or entity tables
3. If still miss → return generated defaults
4. Every response includes a `source` field (`redis`, `sql_fallback`, `generated_fallback`, etc.)

---

## Repository Structure

```
PFE/
├── api-service/              # Read-side REST API (Spring Boot, port 8081)
│   ├── src/main/java/com/neo/dashboard/
│   │   ├── asr/              # Automatic Speech Recognition (NVIDIA Riva gRPC + NIM REST)
│   │   ├── assistant/        # Dashboard AI Assistant (NL → command pipeline)
│   │   ├── config/           # CORS, Jackson, LLM, Security, PasswordEncoder
│   │   ├── controller/       # REST controllers (v1 + V3.6.1 endpoints)
│   │   ├── dto/              # Shared response DTOs + V3.6.1 DTOs (25+ files)
│   │   ├── entity/           # JPA entities (read-only)
│   │   ├── exception/        # Global handler + custom exceptions
│   │   ├── mapper/           # MapStruct mappers
│   │   ├── redis/            # Redis cache key constants
│   │   ├── repository/       # Spring Data JPA repositories
│   │   ├── security/         # JWT token provider, filter, hash util
│   │   └── service/          # Business logic (LLM, alerts, dashboards, etc.)
│   ├── src/main/resources/   # application.yaml, AI reports, email templates
│   ├── src/test/             # JUnit 5 + Testcontainers test suite
│   ├── docs/                 # Endpoint reference, assistant docs, UI registry
│   ├── pom.xml               # Spring Boot 4.0.3, Java 25
│   └── README.md             # Service-specific documentation
│
├── Data Processor/           # ML analytics engine (non-web worker)
│   ├── src/main/java/com/noveocare/dataprocessor/
│   │   ├── ai/               # ML inference internals
│   │   │   ├── artifact/     # AI resource loading & validation
│   │   │   ├── churn/        # Churn/dropoff prediction (ExtraTrees)
│   │   │   ├── explanation/  # LLM evidence payload generation
│   │   │   ├── forecast/     # Volumetric forecasting (XGBoost, Ridge)
│   │   │   ├── persona/      # K-Means persona clustering (disabled)
│   │   │   ├── sequence/     # Sequence anomaly detection (Transformer, TCN ONNX)
│   │   │   ├── tabular/      # Tabular anomaly detection (XGBoost, LightGBM, CatBoost, SVM)
│   │   │   └── tree/         # Custom tree-walking predictors (JSON/TXT)
│   │   ├── config/           # 25+ configuration property classes
│   │   ├── dto/              # Data transfer objects (events, alerts, insights)
│   │   ├── entity/           # JPA entities (writer)
│   │   ├── inference/        # Risk fusion, rules, model orchestration
│   │   ├── kafka/            # Kafka consumer + producer
│   │   ├── mapper/           # Object mapping
│   │   ├── redis/            # Redis data access
│   │   ├── repository/       # Spring Data JPA repositories
│   │   └── service/          # Business logic (sessions, dashboards, scheduling)
│   ├── src/main/resources/   # application.yaml, AI models/configs, 22 DB migrations
│   │   ├── AI/models/        # ONNX (.onnx, .ubj), JSON, TXT model files
│   │   ├── AI/config/        # Score configs, feature contracts, vocabularies
│   │   └── AI/reports/       # ML benchmark reports (CSV, MD)
│   ├── src/test/             # Test suite
│   ├── docs/                 # Scoring reference, persistence strategy, alert contract
│   ├── pom.xml               # Spring Boot 4.0.3, Java 25
│   └── README.md             # Service-specific documentation
│
├── Infra/                    # Core infrastructure
│   ├── docker-compose.yml    # Kafka 7.7.7 (KRaft), Redis 8.2, Kafka Connect
│   ├── docker-compose-monitoring.yml  # Prometheus + Grafana (alt config)
│   ├── Dockerfile-connect    # Custom Kafka Connect with CosmosDB + Elasticsearch connectors
│   ├── cosmos-source-config.json # CosmosDB source connector configuration
│   └── prometheus.yml        # Prometheus scrape config
│
├── elk/                      # Centralized logging stack
│   ├── docker-compose.yml    # Elasticsearch 8.12, Kibana 8.12, Logstash 8.12
│   └── logstash.conf         # TCP input (Spring Boot) + HTTP input (Quasar) → Elasticsearch
│
├── monitoring/               # Metrics & observability
│   ├── docker-compose.yml    # Prometheus + Grafana
│   └── prometheus.yml        # Scrapes api-service (:8081) and dataprocessor (:8080)
│
├── pfe-devops/               # Self-hosted DevOps
│   ├── docker-compose.yml    # GitLab CE + GitLab Runner
│   ├── gitlab-config/        # GitLab configuration (gitlab.rb, SSH keys, secrets)
│   ├── gitlab-data/          # GitLab runtime data (git-ignored)
│   └── gitlab-logs/          # GitLab logs (git-ignored)
│
├── scripts/                  # Simulation & data generation
│   ├── SimData_v3_final.ipynb          # Jupyter notebook for dataset generation
│   ├── v36_normal_traffic_simulator_fixed.py  # Normal traffic Kafka simulator
│   ├── v36_anomaly_traffic_simulator_fixed.py # Anomalous traffic Kafka simulator
│   ├── live_simulator_support.py       # Shared support utilities
│   ├── actions_order-v2.json           # Action sequence definitions
│   └── backend-apis-actions.json       # API action mappings
│
├── data/                    # Static data files
│   ├── actions_order-v2.json
│   ├── backend-apis-actions.json
│   ├── audit_trail_2025_session_summary.csv
│   ├── audit_trail_2025.csv
│   └── simulator_yearly_dataset_v3.py
│
├── .gitlab-ci.yml           # GitLab CI/CD pipeline (test + build stages)
├── .gitignore               # Git ignore rules
└── .opencode-summary.md     # Session summary
```

---

## Core Services

### Data Processor

**Purpose**: Event-driven, ML-powered background worker that ingests audit trails in real time and produces risk analytics.

**Key characteristics**:
- **Non-web worker**: `spring.main.web-application-type: none` — no embedded server
- **Kafka consumer**: Listens on `topic-audit-trail`, publishes to `topic-anomaly-alerts`
- **Redis writer**: Session buffers, insights, live alerts, dashboard snapshots, stats
- **SQL Server writer**: Durable storage via 22 Liquibase migrations
- **ML inference engine**: Supports 8+ model types across tabular, sequence, churn, and forecast domains

**Read more**: `Data Processor/README.md`, `Data Processor/docs/scoring-reference.md`

### API Service

**Purpose**: Read-side REST API that serves pre-computed analytics to the Quasar frontend.

**Key characteristics**:
- **Read-side only**: Never runs ML inference, never owns DB migrations
- **Redis first, SQL fallback**: Multi-tier read strategy for every endpoint
- **LLM integration**: NVIDIA NIM (Nemotron) or Gemini for alert explanations
- **Dashboard Assistant**: Natural language → frontend command pipeline with allowlist validation
- **ASR integration**: NVIDIA Riva (gRPC) / NVIDIA NIM (REST) for speech transcription
- **SSE streaming**: Real-time live alerts and dashboard refreshes via Redis pub/sub
- **30+ REST endpoints**: Auth, alerts, investigations, User 360, churn, forecast, runtime health, LLM explanations

**Read more**: `api-service/README.md`, `api-service/docs/api-service-v36-endpoints.md`

---

## Infrastructure

### Kafka + Redis + Kafka Connect

Located in `Infra/docker-compose.yml`:

| Service | Technology | Purpose |
|---------|-----------|---------|
| **Kafka** | Confluent 7.7.7 (KRaft mode) | Event bus — no Zookeeper dependency |
| **Redis** | Redis 8.2.4 | Live cache, session buffers, real-time state |
| **Kafka Connect** | Custom build | CosmosDB source connector + Elasticsearch sink connector |

Networking: All services share `shared-net` (external Docker network).

**Kafka topics**:
| Topic | Purpose |
|-------|---------|
| `topic-audit-trail` | Inbound audit trail events (input) |
| `topic-anomaly-alerts` | Published anomaly alerts (output) |
| `topic-audit-trail-dlq` | Dead-letter queue for failed events |

### ELK Stack

Located in `elk/docker-compose.yml`:

| Service | Version | Purpose |
|---------|---------|---------|
| Elasticsearch | 8.12.0 | Log storage & search |
| Kibana | 8.12.0 | Log visualization |
| Logstash | 8.12.0 | Log ingestion pipeline |
| | | TCP :5000 ← Spring Boot logs |
| | | HTTP :5001 ← Quasar frontend logs |

### Monitoring

Located in `monitoring/docker-compose.yml`:

| Service | Purpose |
|---------|---------|
| **Prometheus** | Metrics scraping (api-service :8081, dataprocessor :8080) |
| **Grafana** | Dashboard visualization (admin/admin) |

---

## DevOps

Located in `pfe-devops/docker-compose.yml`:

| Service | Purpose |
|---------|---------|
| **GitLab CE** | Self-hosted Git repository, CI/CD, registry |
| **GitLab Runner** | Executes CI/CD pipelines (Docker executor) |

---

## ML Models & Scoring Strategy

### Detection Tiers

| Tier | Components | Weight in Fusion |
|------|-----------|-----------------|
| **Tier 1: Rules** | 15 deterministic heuristic rules | 0.15 |
| **Tier 2: Tabular** | XGBoost (0.30), LightGBM (0.25), CatBoost (context), OneClassSVM (context) | 0.55 |
| **Tier 3: Sequence** | Transformer ONNX (0.20), TCN ONNX (0.10) | 0.30 |

### Fusion Formula

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
| MEDIUM | 35.0 – 59.99 | Suspicious (triggers anomaly alert) |
| HIGH | 60.0 – 79.99 | High risk (standalone alert) |
| CRITICAL | 80.0 – 100.0 | Critical alert |

### Alert Decision Logic

An alert is published if any condition is met:
1. `isAnomaly()` = true (finalRisk ≥ 35.0)
2. finalRisk ≥ 60.0 (sessionAlertRiskThreshold)
3. Session has not already had an alert (Redis deduplication)

### Load Shedding

| Kafka Lag | Behavior |
|-----------|----------|
| ≥ 5,000 | Use TCN instead of Transformer |
| ≥ 10,000 | Skip all ML models, use heuristics only |
| ≥ 20,000 | Rules-only mode |

### Heuristic Rules (15 total)

| Rule | Score Contribution | Severity |
|------|-------------------|----------|
| OFF_HOURS_ACCESS | 25.0 | MEDIUM |
| SENSITIVE_API_OFF_HOURS | 25.0 | MEDIUM |
| STATUS_CODE_BURST | 35.0 | MEDIUM |
| UNUSUAL_DEVICE | 35.0 | MEDIUM |
| DEVICE_SWITCH | 35.0 | MEDIUM |
| API_SCRAPING_PATTERN | 35.0 | MEDIUM |
| COUNTRY_SWITCH | 40.0 | HIGH |
| UNUSUAL_COUNTRY | 40.0 | HIGH |
| LARGE_DOWNLOAD | 40.0 | HIGH |
| DATA_EXTRACTION_PATTERN | 40.0 | HIGH |
| RAPID_FIRE_EVENTS | 30.0 | MEDIUM |
| SKIP_LOGIN | 30.0 | MEDIUM |
| IMPOSSIBLE_ENDPOINT_TRANSITION | 30.0 | MEDIUM |
| SENSITIVE_API_OUTSIDE_BUSINESS_CONTEXT | 30.0 | MEDIUM |
| SESSION_TIMEOUT | 20.0 | LOW |

### Model Files

Located in `Data Processor/src/main/resources/AI/models/`:

- **Sequence models**: `transformer_sequence_engine.onnx`, `tcn_sequence_engine.onnx`, `winning_sequence_engine.onnx`
- **Tabular anomaly models**: `anomaly_xgboost.ubj` / `.json`, `anomaly_lightgbm.txt`, `anomaly_catboost.cbm`, `anomaly_oneclasssvm.json`
- **Churn model**: `churn_profile_only_ExtraTrees.json`
- **Forecast models**: `macro_forecaster_total_events_XGBoost.ubj`, `macro_forecaster_anomaly_rate_Ridge.json`

---

## Getting Started

### Prerequisites

- **Java 25** (JDK 25+)
- **Maven 3.9+** (wrapped via `mvnw.cmd`)
- **Docker Desktop** with WSL2 backend
- **Python 3.10+** (for simulators)
- **SQL Server** instance (or Docker)
- **NVIDIA API key** (for LLM explanations and ASR)
- **FFmpeg** (optional, for ASR audio conversion)

### Quick Start

```powershell
# 1. Create shared Docker network
docker network create shared-net

# 2. Start core infrastructure (Kafka KRaft + Redis + Kafka Connect)
cd Infra
docker compose up -d

# 3. Start Data Processor
cd "Data Processor"
cp .env.example .env   # Edit with your values
.\mvnw.cmd spring-boot:run

# 4. In another terminal, start API Service
cd api-service
cp .env.example .env   # Edit with your values
.\mvnw.cmd spring-boot:run

# 5. (Optional) Start simulators to generate test data
cd scripts
python v36_normal_traffic_simulator_fixed.py --kafka-bootstrap localhost:9092
```

### Running with Docker (Jib)

```powershell
# Build both services
cd "Data Processor"; .\mvnw.cmd compile jib:dockerBuild
cd api-service; .\mvnw.cmd compile jib:dockerBuild
```

### Optional Infrastructure

```powershell
# Monitoring stack
cd monitoring; docker compose up -d

# ELK stack
cd elk; docker compose up -d

# Vault (secrets management)
docker compose -f api-service/docker-compose.vault.yaml up -d
docker compose -f "Data Processor/docker/docker-compose.vault.yaml" up -d

# Self-hosted GitLab
cd pfe-devops; docker compose up -d
```

---

## Environment Variables

### Data Processor

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | JDBC SQL Server localhost | Database JDBC URL |
| `DB_USERNAME` | `app_user` | Database user |
| `DB_PASSWORD` | `change_me` | Database password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `KAFKA_CONSUMER_CONCURRENCY` | `5` | Listener threads |
| `AUDIT_TRAIL_TOPIC` | `topic-audit-trail` | Input topic |
| `ANOMALY_ALERTS_TOPIC` | `topic-anomaly-alerts` | Output topic |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `AI_INFERENCE_ENABLED` | `true` | Master inference toggle |

### API Service

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_USERNAME` | — | SQL Server username |
| `SPRING_DATASOURCE_PASSWORD` | — | SQL Server password |
| `NVIDIA_API_KEY` | — | NVIDIA NIM API key (LLM + ASR) |
| `JWT_SECRET` | — | 64+ char secret for JWT signing |
| `SPRING_AI_GOOGLE_GENAI_API_KEY` | — | Gemini API key (optional fallback) |
| `SPRING_MAIL_USERNAME` | — | SMTP username (email verification) |
| `SPRING_MAIL_PASSWORD` | — | SMTP password |

---

## CI/CD Pipeline

Defined in `.gitlab-ci.yml` with two stages:

### Test Stage
- `test_dataprocessor`: `mvn clean test` in Data Processor
- `test_api_service`: `mvn clean test` in api-service

### Build Stage
- `build_dataprocessor`: `mvn clean package -DskipTests` + JAR listing
- `build_api_service`: `mvn clean package -DskipTests` + JAR listing

Uses `maven:3.9-eclipse-temurin-25` Docker image with Maven cache.

---

## Documentation Index

### Service Documentation

| Document | Location | Description |
|----------|----------|-------------|
| API Service README | `api-service/README.md` | Service architecture, endpoints, configuration |
| Data Processor README | `Data Processor/README.md` | Pipeline, package map, ML strategy, Redis keys |
| api-service-v36-endpoints.md | `api-service/docs/` | Complete V3.6.1 endpoint reference (1163 lines) |
| api-service-dashboard-assistant.md | `api-service/docs/` | Dashboard Assistant architecture & commands |
| dashboard-assistant-ui-registry.md | `api-service/docs/` | Frontend/backend ID consistency checklist |

### Data Processor Documentation

| Document | Location | Description |
|----------|----------|-------------|
| scoring-reference.md | `Data Processor/docs/` | Full scoring reference — fusion formula, per-model details, thresholds |
| persistence-strategy.md | `Data Processor/docs/` | Redis + SQL durable architecture, dashboard snapshot fallback |
| alert-payload-contract.md | `Data Processor/docs/` | Complete AnomalyAlert DTO schema, Redis cached payload, SQL mapping |

### Database Migrations

Located in `Data Processor/src/main/resources/db/migration/` (22 migrations):
- `V1__init.sql` — Initial schema
- `V2-V6` — Observability & timestamp alignment
- `V7-V12` — AI contract alignment, indexes, users
- `V13-V22` — V3.4 through V3.6 feature fields, hybrid runtime, anomaly events, LLM explanations, next-event predictions

### AI Model Benchmarks

Located in `Data Processor/src/main/resources/AI/reports/`:
- `final_use_case_winners.json` / `.csv` / `.md` — Winning model selection
- `tabular_anomaly_benchmarks.csv` — Tabular model benchmarks
- `churn_benchmarks.csv` / `.md` — Churn model benchmarks
- `macro_forecast_benchmarks.csv` / `.md` — Forecast benchmarks
- `persona_benchmarks.csv` / `.md` — Persona clustering benchmarks
- `model_benchmarks.md` — Aggregate model comparison

---

## Tech Stack Summary

| Component | Technology |
|-----------|-----------|
| Language | Java 25 |
| Framework | Spring Boot 4.0.3 |
| ML Runtime | ONNX Runtime, CatBoost, custom tree-walkers |
| Event Bus | Apache Kafka 7.7.7 (KRaft) |
| Cache | Redis 8.2 |
| Database | SQL Server |
| REST API | Spring WebFlux + MVC (port 8081) |
| LLM | NVIDIA NIM (Nemotron), Gemini (fallback) |
| ASR | NVIDIA Riva (gRPC), NVIDIA NIM (REST) |
| Auth | JWT (jjwt), Spring Security |
| Build | Maven, Jib (Docker) |
| Testing | JUnit 5, Instancio, Testcontainers |
| Observability | Prometheus, Grafana, ELK Stack |
| CI/CD | GitLab CE + GitLab Runner |
| Simulation | Python 3.10+, Jupyter |
| DevOps | Docker Compose, HashiCorp Vault |

---

## Related Projects

- **Quasar Frontend**: Vue 3/Quasar SPA consuming the API service endpoints (separate repository)
- **HashiCorp Vault**: Sidecar secrets management for both services
- **Cosmos DB**: Azure source database ingested via Kafka Connect
