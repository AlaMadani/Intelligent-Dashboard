# Data Processor — NoveoCare ML Inference Engine

A **Spring Boot 4.0.3** background worker that consumes Kafka audit-trail events, enriches them in Redis, runs a **multi-model ML ensemble** via ONNX Runtime, and persists session-level risk analyses to SQL Server. Designed for real-time anomaly detection, user profiling, churn prediction, and volumetric forecasting in a telecom/OTT platform security context.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Data Flow Pipeline](#data-flow-pipeline)
3. [ML Models & Mathematical Theory](#ml-models--mathematical-theory)
   - [XGBoost — Binary Anomaly Detector](#1-xgboost--binary-anomaly-detector)
   - [Isolation Forest — Unsupervised Outlier Detector](#2-isolation-forest--unsupervised-outlier-detector)
   - [Random Forest — Anomaly Type Classifier](#3-random-forest--anomaly-type-classifier)
   - [Random Forest — Churn/Dropoff Predictor](#4-random-forest--churndropoff-predictor)
   - [K-Means — Persona Clustering](#5-k-means--persona-clustering)
   - [Markov Chain — Path Deviation & Next-Action Prediction](#6-markov-chain--path-deviation--next-action-prediction)
   - [Prophet — Volumetric Time-Series Forecasting](#7-prophet--volumetric-time-series-forecasting)
4. [Ensemble Risk Score](#ensemble-risk-score)
5. [Feature Engineering](#feature-engineering)
6. [Heuristic Rules](#heuristic-rules)
7. [AI Artifacts Directory](#ai-artifacts-directory)
8. [Configuration Reference](#configuration-reference)
9. [Database Schema](#database-schema)
10. [Redis Key Layout](#redis-key-layout)
11. [Development Setup](#development-setup)
12. [Testing](#testing)
13. [Docker Build](#docker-build)

---

## Architecture Overview

```
┌─────────────┐     ┌──────────────────────────────────────────────────────┐
│   Kafka     │     │                 Data Processor                       │
│ audit-trail ├────►│                                                    │
│   topic     │     │  KafkaConsumer ──► RedisSessionBuffer               │
└─────────────┘     │       │                                             │
                    │       ▼                                             │
                    │  FeatureEngineeringService                          │
                    │  ┌─ enrichSessionEvents()                           │
                    │  └─ buildSessionSummary()                           │
                    │       │                                             │
                    │       ▼                                             │
                    │  RuleEvaluator (heuristic triggers)                  │
                    │  ┌─ unusual_hour, skip_login                        │
                    │  ├─ repeated_fail, rapid_fire                       │
                    │  ├─ geo_jump, session_timeout                       │
                    │  └─ impossible_seq                                  │
                    │       │                                             │
                    │       ▼                                             │
                    │  ModelInferenceService (ONNX Runtime)               │
                    │  ┌─ detectBinaryAnomaly() [XGBoost / Isolation Forest]
                    │  ├─ classifyAnomaly()       [Random Forest]         │
                    │  ├─ predictChurn()          [Random Forest]         │
                    │  ├─ predictCluster()        [K-Means Pipeline]      │
                    │  └─ TransitionMatrixService [Markov Chain]          │
                    │       │                                             │
                    │       ▼                                             │
                    │  computeEnsembleRisk() ─► SessionInsight             │
                    │       │                                             │
                    │       ├──► Kafka (anomaly-alerts topic)             │
                    │       ├──► Redis (cache + PubSub)                   │
                    │       └──► SQL Server (SessionAnalysis + entities)  │
                    │                                                      │
                    │  TrendPredictionScheduler (Prophet CSV)             │
                    │  └──► evaluateSystemTrafficAnomaly()                │
                    │       └──► Kafka SYSTEM_TRAFFIC_ANOMALY alert        │
                    └──────────────────────────────────────────────────────┘
```

The Data Processor is a **non-web Spring Boot application** (`spring.main.web-application-type: none`). It runs as a background worker consuming from Kafka, processing events through a multi-stage ML pipeline, and publishing results to Redis, Kafka, and SQL Server.

---

## Data Flow Pipeline

### 1. Kafka Consumption (`AuditTrailConsumer.java`)

- Listens on `topic-audit-trail` with configurable concurrency (default 5)
- Deserializes JSON into `AuditTrailEvent` DTOs
- Buffers per-session events in Redis (`RedisSessionBufferService`)
- Records live statistics (event counts, action counts, KO rates, etc.) in Redis counters

### 2. Feature Engineering (`FeatureEngineeringService.java`)

Two main transforms:

**`enrichSessionEvents()`** — Computes per-event derived features:
- Time deltas from previous event
- IP/device change flags (compared to first event in session)
- Cumulative KO streaks and longest streak
- Sliding-window download count (configurable window, default 120s)
- Ping-pong loop detection (A→B→A→B pattern: same action at index n and n-2, different at n-1)
- Risk score per event (weighted sum of signals)
- Session duration, hour of day, day of week, weekend flag

**`buildSessionSummary()`** — Aggregates enriched events into `SessionSummary`:
- Counts (total events, KOs, OKs, downloads, unique IPs, devices, routes, actions)
- Duration statistics (total, avg/min/max inter-action seconds)
- Binary flags (hasLogin, hasLogout, ipChanged, deviceChanged, endedAbruptly)
- Sequences (action sequence, route sequence, action counts map)
- Signatures (joined sequences for pattern matching)
- Anomaly metadata (event count, types, primary type)

### 3. Tabular Feature Vector Construction (`buildTabularFeatures()`)

For ONNX model inference, the `SessionSummary` is converted to a `float[]` vector:

1. Start with the full list of feature column names (from the model's `*_feature_columns.json`)
2. For each column:
   - If it's a **numeric feature** (in `session_numeric_medians`): extract the value from `SessionSummary`, fall back to the median for missing values
   - If it's a **categorical feature** (in `session_categorical_features`): match against `{featureName}_{value}` one-hot encoded column name
3. The result is a fixed-size `float[]` ready for ONNX tensor input

### 4. Inference Pipeline (`ModelInferenceService.infer()`)

Sequential inference pipeline per session:

```
infer(summary, enrichedEvents, triggeredRules)
  │
  ├── 1. detectBinaryAnomaly()
  │      XGBoost ONNX → probability for class 1, threshold ≥ 0.5
  │      Fallback: Isolation Forest ONNX → label < 0 || score ≥ isoThreshold
  │      Fallback: heuristic (ipChange, deviceChange, KOs ≥ 3, downloads ≥ 10, pingPong ≥ 2)
  │
  ├── 2. evaluatePathDeviation()
  │      Markov Chain → last transition probability < 0.02
  │
  ├── 3. rareTransitions()
  │      Markov Chain → all transitions with probability < 0.02
  │
  ├── 4. overallAnomaly = binaryResult.anomalyFlag || pathDeviation.deviated || triggeredRules not empty
  │
  ├── 5. classifyAnomaly()  (only if overallAnomaly)
  │      Random Forest ONNX → 11-class type + confidence
  │      Fallback: heuristicType() rule cascade
  │
  ├── 6. predictChurn()
  │      Random Forest ONNX → probability for class 1
  │      Fallback: heuristic formula (endedAbruptly base + KO + download + pingPong boosts)
  │
  ├── 7. predictCluster()
  │      K-Means Pipeline ONNX → cluster label (0–7)
  │
  ├── 8. predictNextActions()
  │      Markov Chain → top 3 next actions by transition probability
  │
  ├── 9. computeEnsembleRisk()
  │      Weighted formula (see §Ensemble Risk Score)
  │
  ├── 10. buildFeatureContributions()
  │       Top 5 features from pre-computed importance CSV
  │       Fallback: heuristic contributions
  │
  └── 11. buildExplainabilityText()
          Natural language explanation joining type + top 3 features + triggered rules
```

### 5. Load Shedding

When Kafka consumer lag exceeds `loadSheddingLagThreshold` (default 10000), `inferLightweight()` is used instead — it skips all ML models and uses pure heuristics, publishing a warning explaining the reason.

### 6. Outputs

- **Kafka:** `AnomalyAlert` published to `topic-anomaly-alerts`
- **Redis:**
  - Session insight cached under `session:insight:{insuredId}:{sessionId}`
  - User risk profile under `risk:{insuredId}`
  - Dashboard snapshots under `dashboard:{view}`
  - PubSub notifications on `LIVE_STATS` channel
- **SQL Server:**
  - `SessionAnalysis` (full session-level analysis with all scores)
  - `AnomalyEvent` (individual alert records)
  - `NextActionPrediction` (latest top-3 next actions per user)
  - `UserRiskProfile` (aggregated 7d/30d risk profile)

### 7. Scheduled Jobs

| Job | Frequency | Description |
|---|---|---|
| `LiveStatsScheduler` | Every 5s | Refreshes live stats snapshot in Redis |
| `TrendPredictionScheduler` | Every 30s | Refreshes forecast dashboard + evaluates system traffic anomaly |

---

## ML Models & Mathematical Theory

### 1. XGBoost — Binary Anomaly Detector

**File:** `xgb_binary.onnx` (preferred), `xgb_binary.joblib` (training)

**Purpose:** Identify individual sessions as anomalous (binary yes/no) based on labeled historical examples. This is the primary supervised anomaly detector.

**Type:** Supervised binary classification

**Output:** Probability of class 1 (anomaly). Threshold ≥ 0.5 → flagged.

**Mathematical Theory:**

XGBoost (eXtreme Gradient Boosting) is an ensemble of **K decision trees** built sequentially, where each tree corrects the errors of the previous ones. Formally:

$$\hat{y}_i = \sum_{k=1}^{K} f_k(x_i), \quad f_k \in \mathcal{F}$$

where $\mathcal{F}$ is the space of regression trees. The objective at step $t$:

$$\mathcal{L}^{(t)} = \sum_{i=1}^{n} \ell(y_i, \hat{y}_i^{(t-1)} + f_t(x_i)) + \Omega(f_t)$$

where $\ell$ is the logistic loss for binary classification:

$$\ell(y, \hat{y}) = -y \log(\sigma(\hat{y})) - (1-y) \log(1 - \sigma(\hat{y}))$$

and $\Omega(f) = \gamma T + \frac{1}{2}\lambda \sum_{j=1}^{T} w_j^2$ is the regularization term penalizing tree complexity ($T$ leaves, $w_j$ leaf weights).

The gradient boosting uses a **second-order Taylor expansion** for efficient optimization:

$$\mathcal{L}^{(t)} \approx \sum_{i=1}^{n} [g_i f_t(x_i) + \frac{1}{2} h_i f_t^2(x_i)] + \Omega(f_t)$$

where $g_i = \partial_{\hat{y}^{(t-1)}} \ell(y_i, \hat{y}^{(t-1)})$ and $h_i = \partial^2_{\hat{y}^{(t-1)}} \ell(y_i, \hat{y}^{(t-1)})$ are the first and second derivatives (gradients and hessians).

The optimal leaf weight for a given tree structure is:

$$w_j^* = -\frac{\sum_{i \in I_j} g_i}{\sum_{i \in I_j} h_i + \lambda}$$

The final output is passed through the sigmoid function:

$$P(y=1|x) = \sigma(\hat{y}) = \frac{1}{1 + e^{-\hat{y}}}$$

**Features:** 75 features (24 session numerics + 6 categorical one-hot encoded + interaction/dummy features)

**Training:** Supervised on synthetic sessions (labeled by simulator with 5% anomaly injection rate)

---

### 2. Isolation Forest — Unsupervised Outlier Detector

**File:** `iso_binary.onnx` (fallback), `iso_binary.joblib` (training)

**Purpose:** Flag sessions that are mathematically "different" from normal traffic, even if that behavior was never explicitly labeled during training.

**Type:** Unsupervised anomaly detection

**Output:** Binary label (1 = normal, -1 = anomaly) with anomaly score. The Java runtime checks `label < 0 || score >= isoThreshold` (threshold = 0.526 from `feature_bundle.json`).

**Mathematical Theory:**

Isolation Forest isolates anomalies by randomly **partitioning the feature space** with decision trees. The core insight: anomalies are **few and different**, so they require fewer random splits to isolate.

Each tree $t$ in the forest of $T$ trees recursively splits the data by randomly selecting a feature $q$ and a split value $v$ between the min and max of that feature. The **path length** $h(x)$ for a point $x$ is the number of edges traversed from root to the terminating leaf.

The **anomaly score** is:

$$s(x, n) = 2^{-\frac{E[h(x)]}{c(n)}}$$

where $E[h(x)]$ is the average path length across all $T$ trees, $n$ is the number of training samples, and:

$$c(n) = 2H(n-1) - \frac{2(n-1)}{n}$$

is the average path length of an unsuccessful BST search, with $H(i) = \ln(i) + 0.5772156649$ (Euler-Mascheroni constant).

Interpretation:
- $s \approx 0.5$ → no clear distinction (normal)
- $s \to 1$ → definite anomaly (short path)
- $s \to 0$ → definite inlier (long path)

**ONNX export limitation:** The scikit-learn Isolation Forest's `decision_function` and `score_samples` may not perfectly survive the `skl2onnx` conversion due to random leaf node behavior in 75 dimensions. The model is used as a **fallback** when XGBoost is unavailable.

---

### 3. Random Forest — Anomaly Type Classifier

**File:** `rf_type.onnx`, `rf_type.joblib` (training), `rf_type_labels.json` (11 classes)

**Purpose:** Once a session is flagged as anomalous, classify it into one of 11 specific attack vectors.

**Type:** Multi-class supervised classification (11 classes)

**Output:** Class label (0–10) with confidence score (probability for the predicted class)

**Mathematical Theory:**

Random Forest is an **ensemble of $B$ decorrelated decision trees**. Each tree is trained on a bootstrap sample of the training data, and at each split only a random subset of $m$ features (typically $\sqrt{p}$ for classification, where $p$ is the total number of features) is considered.

For classification, each tree votes for a class, and the forest returns the **modal class**:

$$\hat{y} = \text{mode}\{\hat{y}_b\}_{b=1}^{B}$$

The **class probability** is estimated as the proportion of trees voting for that class:

$$\hat{P}(y = c|x) = \frac{1}{B} \sum_{b=1}^{B} \mathbb{I}(\hat{y}_b = c)$$

Each tree partitions the feature space $\mathbb{R}^p$ into axis-aligned rectangles (leaf regions). For a query point $x$, the prediction is the majority class of training points falling in the same leaf.

**11 Anomaly Types:**

| ID | Type | Description |
|----|------|-------------|
| 0 | `data_exfiltration` | Bulk download / document extraction |
| 1 | `distributed_brute_force` | Multi-credential attack |
| 2 | `geo_jump` | Impossible country change |
| 3 | `impossible_device_switch` | Device identity swap mid-session |
| 4 | `impossible_seq` | Statistically impossible action path |
| 5 | `ping_pong_loop` | Repeated navigation oscillation |
| 6 | `rapid_fire` | Sub-second action bursts |
| 7 | `repeated_fail` | Consecutive KO errors |
| 8 | `skip_login` | Access without authentication |
| 9 | `unusual_hour` | Activity outside normal hours (2:00–4:00) |
| 10 | `zombie_session` | Long-lived session with no logout |

**Heuristic Fallback** (when ONNX model unavailable): A rule cascade assigns deterministic types based on triggered rules and session features, with fixed confidence values (0.65–0.80).

---

### 4. Random Forest — Churn/Dropoff Predictor

**File:** `rf_churn.onnx`, `rf_churn.joblib` (training)

**Purpose:** Predict the probability that a session ended abruptly (user abandoned the portal without explicit logout), indicating UX friction or a disconnected bot.

**Type:** Binary supervised classification

**Output:** Probability of class 1 (will end abruptly / churn)

**Mathematical Theory:**

Same Random Forest ensemble theory as above (§3), but for binary classification. The model is trained on 56 features with the target `endedAbruptly` (1 = session ended without logout action after meaningful activity, 0 = normal session with logout or trivial visit).

The "churn" label is defined as: `!hasLogout && totalEvents >= 3`. This excludes trivial single-page visits from the abrupt-ending category. The RF model captures nuanced patterns beyond the simple heuristic: high KO rates, rapid action bursts, download spikes, and navigation anomalies all contribute to the probability.

**Heuristic Fallback** (when ONNX model unavailable):

$$P_{\text{churn}} = \min(1.0, \; 0.5 \cdot \mathbb{I}_{\text{endedAbruptly}} + 0.2 \cdot \mathbb{I}_{\text{KOs} \geq 3} + 0.15 \cdot \mathbb{I}_{\text{downloads} \geq 10} + 0.15 \cdot \mathbb{I}_{\text{pingPong} \geq 2})$$

---

### 5. K-Means — Persona Clustering

**File:** `kmeans_persona_pipeline.onnx` (scaler + KMeans), `kmeans_persona.joblib`, `cluster_scaler_params.json`

**Purpose:** Unsupervised grouping of sessions into behavioral personas for dashboard segmentation and user profiling.

**Type:** Unsupervised clustering

**Output:** Cluster label (integer 0–7, mapped to persona names in the frontend)

**Mathematical Theory:**

K-Means partitions $n$ sessions into $k$ clusters, each represented by its centroid $\mu_j$. The objective is to minimize the **within-cluster sum of squares (WCSS)**:

$$\min_{\mu_1,\dots,\mu_k} \sum_{j=1}^{k} \sum_{x_i \in C_j} \|x_i - \mu_j\|^2$$

Using the standard **Lloyd's algorithm**:

1. Initialize $k$ centroids (random from data points)
2. **Assignment step:** Assign each point to the nearest centroid:

$$C_j^{(t)} = \{x_i : \|x_i - \mu_j^{(t)}\|^2 \leq \|x_i - \mu_l^{(t)}\|^2 \; \forall l \in \{1,\dots,k\}\}$$

3. **Update step:** Recompute centroids as the mean of assigned points:

$$\mu_j^{(t+1)} = \frac{1}{|C_j^{(t)}|} \sum_{x_i \in C_j^{(t)}} x_i$$

4. Repeat steps 2–3 until convergence (centroids stabilize)

**Distance metric:** Euclidean distance. The features are **standardized** (z-scored) before clustering using `StandardScaler` to prevent high-magnitude features from dominating. The scaler parameters are stored in `cluster_scaler_params.json` and the pipeline is exported as a single ONNX file.

**10 Features:**
- `totalEvents` — number of audit events
- `totalDurationSeconds` — session wall-clock duration
- `avgInterActionSeconds` — average time between events
- `uniqueActions` — distinct action types
- `totalKOs` — error count
- `uniqueIpsUsed` — distinct IP addresses
- `uniqueDevicesUsed` — distinct device identifiers
- `totalDownloadActions` — download events
- `maxDownloadsIn2Minutes` — peak download rate
- `pingPongCount` — navigation loop count

**K selection:** Determined during training via elbow method or silhouette analysis (fixed at 8 clusters based on synthetic data evaluation). Cluster labels correspond to behavioral archetypes (Power Users, Idle Sessions, Aggressive Bots, etc.).

---

### 6. Markov Chain — Path Deviation & Next-Action Prediction

**File:** `markov_transition_lookup.json`, `markov_transitions.csv` (fallback)

**Purpose:** Two complementary uses:
1. **Path Deviation Detection:** Find users taking improbable navigation paths (potential attacks)
2. **Next-Action Prediction:** Anticipate the user's next action for proactive security responses

**Type:** First-order discrete Markov chain (probabilistic state machine)

**Mathematical Theory:**

A Markov chain is a stochastic process where the probability of transitioning to the next state depends **only on the current state** (memoryless property):

$$P(X_{t+1} = b \mid X_t = a, X_{t-1} = a_{t-1}, \dots, X_0 = a_0) = P(X_{t+1} = b \mid X_t = a) = \pi_{a \to b}$$

The **transition matrix** $\mathbf{P}$ is a $|S| \times |S|$ stochastic matrix where each row sums to 1:

$$\mathbf{P} = \begin{pmatrix}
\pi_{1 \to 1} & \pi_{1 \to 2} & \cdots & \pi_{1 \to |S|} \\
\pi_{2 \to 1} & \pi_{2 \to 2} & \cdots & \pi_{2 \to |S|} \\
\vdots & \vdots & \ddots & \vdots \\
\pi_{|S| \to 1} & \pi_{|S| \to 2} & \cdots & \pi_{|S| \to |S|}
\end{pmatrix}, \quad \sum_{j} \pi_{i \to j} = 1 \; \forall i$$

Entries are estimated via **maximum likelihood** from training data:

$$\hat{\pi}_{a \to b} = \frac{\text{count}(a \to b)}{\sum_{c \in S} \text{count}(a \to c)} = \frac{n_{ab}}{n_{a\bullet}}$$

where $n_{ab}$ is the number of times action $a$ was followed by action $b$ in the training sessions.

**Path Deviation Detection:**
- For each transition in the session, look up $\pi_{\text{from} \to \text{to}}$ in the matrix
- **Path deviation:** $P(X_{t+1} = b \mid X_t = a) < 0.02$ (configurable `pathDeviation.minProbability`)
- **Impossible transition:** $P < 0.005$ (configurable `impossibleSeq.minProbability`)

**Next-Action Prediction:**
- Given current action $a$, return the top-$n$ actions sorted by $\pi_{a \to \cdot}$

**Limitation:** First-order only. Multi-step attack patterns (e.g., A→B→C where A→B and B→C are individually fine but A→C is anomalous) are not detected, as the model lacks memory of history beyond the immediate state.

---

### 7. Prophet — Volumetric Time-Series Forecasting

**File:** `prophet_total_events.json`, `prophet_anomaly_events.json`, `prophet_download_events.json` (serialized models), `forecast_*.csv` (pre-computed forecasts)

**Purpose:** Three forecasts:
1. **Total events** — expected daily event volume (primary, used for anomaly alerting)
2. **Anomaly events** — expected daily anomaly detection count (dashboard only)
3. **Download events** — expected daily download count (dashboard only)

**Type:** Additive time-series forecasting with changepoint detection

**Output:** Daily forecast with 80% confidence interval, trend component, and seasonality components

**Mathematical Theory:**

Prophet (by Facebook/Meta) models time series as an additive combination of three components:

$$y(t) = g(t) + s(t) + h(t) + \varepsilon_t$$

**1. Trend $g(t)$:** Piecewise linear or logistic growth with automatic changepoint detection

For linear growth:
$$g(t) = (k + \delta(t)^T \mathbf{a}(t)) \cdot t + (m + \delta(t)^T \boldsymbol{\gamma}(t))$$

where:
- $k$ is the base growth rate
- $\delta(t)$ is the vector of rate adjustments at changepoints (Laplace prior: $\delta_j \sim \text{Laplace}(0, \tau)$)
- $\mathbf{a}(t)$ indicates which changepoints have been passed at time $t$
- $m$ and $\boldsymbol{\gamma}$ are the offset terms for continuity

The regularization parameter $\tau$ (`changepoint_prior_scale`) controls how flexible the trend is — higher values allow more changepoints.

**2. Seasonality $s(t)$:** Fourier series approximation

$$s(t) = \sum_{n=1}^{N} \left( a_n \cos\left(\frac{2\pi n t}{P}\right) + b_n \sin\left(\frac{2\pi n t}{P}\right) \right)$$

where $P$ is the period (7 for weekly, 365.25 for yearly). The Fourier coefficients $a_n, b_n$ are learned from data. The number of Fourier terms $N$ controls the flexibility of seasonality.

**3. Holiday effects $h(t)$:** Linear dummy-variable model for known holiday dates (not used in the current model given synthetic data).

**Forecast uncertainty** is estimated via **Markov Chain Monte Carlo** (MCMC) on the posterior, producing uncertainty intervals that capture both the trend uncertainty and the observation noise.

**Runtime usage:** Prophet models are pre-trained in the Jupyter notebook and exported to:
- **CSV files** (`forecast_*.csv`): Pre-computed daily forecast points with `ds`, `yhat`, `yhat_lower`, `yhat_upper`, `trend` columns — loaded at startup and used for dashboard display
- **JSON files** (`prophet_*.json`): Serialized Prophet model parameters — loaded at startup for metadata display (growth mode, seasonality parameters, changepoint prior scale, etc.) but NOT used for live re-forecasting

**System Traffic Anomaly Detection:**
In `TrendPredictionScheduler.evaluateSystemTrafficAnomaly()`, the current day's actual event count is compared against the Prophet forecast using **rate-based projection**:

$$\text{projectedDaily} = \frac{\text{actualEvents}}{\text{hoursElapsed}} \times 24 \quad \text{(requires hoursElapsed ≥ 4)}$$

If `projectedDaily > yhatUpper`, a `SYSTEM_TRAFFIC_ANOMALY` alert is published to Kafka. This approach avoids the false-positive-prone linear progress assumption that would assume uniform traffic distribution throughout the day.

---

## Ensemble Risk Score

The `SessionInsight` contains a composite risk score (0–100) computed as:

```
risk = min(100, 
   30 * ipChanged
 + 25 * deviceChanged
 + 15 * (totalKOs >= 3 ? 1 : 0)
 + 15 * (maxDownloadsIn2Minutes >= 10 ? 1 : 0)
 + 15 * (pingPongCount >= 2 ? 1 : 0)
 + 40 * anomalyProbability
 + 15 * churnProbability
)
```

Risk levels:
| Score | Level |
|-------|-------|
| ≥ 80 | HIGH |
| ≥ 50 | MEDIUM |
| < 50 | LOW |

This is a heuristic weighted formula, not a statistically calibrated model. Weights reflect domain expert assessment of the relative severity of each signal (anomaly probability weighted highest at 40 points).

---

## Feature Engineering

### Numeric Features (24)

| Feature | Type | Description |
|---------|------|-------------|
| `totalEvents` | count | Total audit events in session |
| `totalDurationSeconds` | duration | Wall-clock seconds from first to last event |
| `avgInterActionSeconds` | float | Mean time between consecutive events |
| `minInterActionSeconds` | float | Minimum inter-event gap |
| `maxInterActionSeconds` | float | Maximum inter-event gap |
| `uniqueActions` | count | Distinct action types |
| `uniqueRoutes` | count | Distinct navigation routes |
| `uniqueIpsUsed` | count | Distinct IP addresses (min 1) |
| `uniqueDevicesUsed` | count | Distinct device fingerprints (min 1) |
| `totalKOs` | count | KO status events |
| `totalOKs` | count | OK status events |
| `longestKoStreak` | count | Maximum consecutive KOs |
| `hasLogin` | binary | Login action present (0/1) |
| `hasLogout` | binary | Logout action present (0/1) |
| `ipChanged` | binary | IP different from session first |
| `deviceChanged` | binary | Device different from session first |
| `totalDownloadActions` | count | Download-matched events |
| `maxDownloadsIn2Minutes` | count | Peak downloads in sliding 120s window |
| `pingPongCount` | count | Navigation oscillation matches |
| `riskScoreMax` | float | Max per-event risk score |
| `riskScoreAvg` | float | Mean per-event risk score |
| `startHour` | int | Hour of first event (0–23) |
| `endHour` | int | Hour of last event (0–23) |
| `dayOfWeek` | int | Day of week (0=Monday, 6=Sunday) |
| `isWeekend` | binary | Weekend flag (0/1) |

### Categorical Features (6)

One-hot encoded into feature vector:
- `persona` (self_service, power_user, bot, etc.)
- `countryCode` (FR, DZ, MA, etc.)
- `firstAction` (Connexion, Déconnexion, etc.)
- `lastAction`
- `firstRoute`
- `lastRoute`

### Cluster Features (10)

Subset of numeric features optimized for persona clustering:
`totalEvents`, `totalDurationSeconds`, `avgInterActionSeconds`, `uniqueActions`, `totalKOs`, `uniqueIpsUsed`, `uniqueDevicesUsed`, `totalDownloadActions`, `maxDownloadsIn2Minutes`, `pingPongCount`

---

## Heuristic Rules

Before ML inference, **deterministic rules** evaluate session events against configured thresholds. These serve both as a fast pre-filter and as a fallback when models are unavailable.

| Rule | Config | Logic |
|------|--------|-------|
| `unusual_hour` | `start: 2, end: 4` | Any event timestamp falls within the window (UTC) |
| `skip_login` | `allowed-actions` list | Session has actions NOT in the allowed pre-login set before any login action |
| `repeated_fail` | `consecutive: 3` | 3+ consecutive KO events of a monitored type (BANKING_ACTIONS, LOGGING_ACTIONS) |
| `rapid_fire` | `min-events: 3, window-seconds: 2` | 3+ events within a 2-second sliding window |
| `geo_jump` | `enabled: true` | Country code changes between consecutive events |
| `session_timeout` | `threshold-seconds: 1200` | Any inter-event gap exceeds 1200s (20min) |
| `impossible_seq` | `min-probability: 0.005` | Markov probability for any transition < 0.005 |
| `path_deviation` | `min-probability: 0.02` | Markov probability for the last transition < 0.02 |

---

## AI Artifacts Directory

Located at `src/main/resources/AI/` — all artifacts loaded via `RuntimeArtifactService` at startup.

### ONNX Models (4 inference models)

| File | Source Algorithm | Input Features | Purpose |
|------|-----------------|----------------|---------|
| `xgb_binary.onnx` | XGBoost | 75 | Binary anomaly detector (preferred) |
| `iso_binary.onnx` | Isolation Forest | 75 | Binary anomaly detector (fallback) |
| `rf_type.onnx` | Random Forest | 75 | 11-class anomaly type classifier |
| `rf_churn.onnx` | Random Forest | 56 | Churn/dropoff probability |
| `kmeans_persona_pipeline.onnx` | StandardScaler + K-Means | 10 | Persona clustering |

### Joblib Models (Python training checkpoints)

| File | Algorithm | Notes |
|------|-----------|-------|
| `xgb_binary.joblib` | XGBoost | Reference for retraining |
| `iso_binary.joblib` | Isolation Forest | Reference for retraining |
| `rf_type.joblib` | Random Forest | Reference for retraining |
| `rf_churn.joblib` | Random Forest | Reference for retraining |
| `kmeans_persona.joblib` | K-Means | Reference for retraining |
| `cluster_scaler.joblib` | StandardScaler | Scaler for cluster features |

### Feature & Configuration JSON

| File | Content |
|------|---------|
| `deployment_manifest.json` | Master artifact registry: model paths, feature columns, medians, forecast metadata |
| `feature_bundle.json` | Feature lists, `isoThreshold`, model capability flags |
| `binary_feature_columns.json` | 75 column names for binary detection feature vector |
| `type_feature_columns.json` | 75 column names for type classification feature vector |
| `churn_feature_columns.json` | 56 column names for churn prediction feature vector |
| `session_feature_columns.json` | 75 column names (general purpose) |
| `session_numeric_medians.json` | Median imputation values for numeric features |
| `churn_numeric_medians.json` | Median imputation values for churn features |
| `rf_type_labels.json` | Integer → anomaly type mapping (11 classes) |
| `cluster_scaler_params.json` | StandardScaler mean/scale per cluster feature |
| `cluster_mix.csv` | Fallback dashboard data for persona distribution |
| `alerts_feed.csv` | Fallback dashboard data for alerts feed |
| `dropoff_actions.csv` | Fallback dashboard data for drop-off actions |
| `path_deviations.csv`, `path_summary.csv` | Fallback dashboard data for path deviations |
| `top_risky_sessions.csv` | Fallback dashboard data for risky sessions |

### Markov Chain

| File | Content |
|------|---------|
| `markov_transition_lookup.json` | Transition probability matrix (JSON, primary source) |
| `markov_transitions.csv` | Transition probability matrix (CSV, merged as fallback) |

### Prophet Forecast

| File | Content |
|------|---------|
| `prophet_total_events.json` | Serialized Prophet model (total events) |
| `prophet_anomaly_events.json` | Serialized Prophet model (anomaly events) |
| `prophet_download_events.json` | Serialized Prophet model (download events) |
| `forecast_total_events.csv` | Pre-computed daily forecast points (ds, yhat, yhat_lower, yhat_upper, trend) |
| `forecast_anomaly_events.csv` | Pre-computed daily forecast points |
| `forecast_download_events.csv` | Pre-computed daily forecast points |
| `forecast_registry.json` | Forecast metadata (MAE, RMSE per series) |

### Feature Importance

| File | Content |
|------|---------|
| `binary_detector_feature_importance.csv` | Feature importance from XGBoost binary model |
| `anomaly_type_feature_importance.csv` | Feature importance from RF type classifier |

### Navigation Graph

| File | Content |
|------|---------|
| `actions_order-v2.json` | Application navigation graph (auth flow + routes) |
| `backend-apis-actions.json` | API-to-action tracking mapping |

### Python Scripts

| File | Lines | Purpose |
|------|-------|---------|
| `simulator_yearly_dataset_v3.py` | 1685 | Full-year synthetic data generator with 5 personas, anomaly injection, Markov path generation |
| `simulator_final_revised.py` | 306 | Live Kafka replay simulator with virtual clock and anomaly mode |
| `live_simulator_support.py` | 303 | Simulator helpers: Markov lookup, session materialization, anomaly matching, rule evaluation |
| `anomaly_kafka_producer.py` | 263 | Continuous anomalous session emitter for testing |

### Notebook

| File | Lines | Purpose |
|------|-------|---------|
| `SimData_v3_final.ipynb` | 947 | Full training pipeline: all 7 models trained, evaluated, exported to ONNX+joblib |

---

## Configuration Reference

### `application.yaml` key sections

| Prefix | Key | Default | Description |
|--------|-----|---------|-------------|
| `app.kafka.topics` | `audit-trail` | `topic-audit-trail` | Input event topic |
| | `anomaly-alerts` | `topic-anomaly-alerts` | Output alert topic |
| | `dlq` | `topic-audit-trail-dlq` | Dead-letter queue |
| `app.kafka.consumer` | `concurrency` | `5` | Kafka listener threads |
| | `load-shedding-lag-threshold` | `10000` | Lag at which ML models are skipped |
| `app.redis.ttl` | `session-buffer` | `30m` | TTL for buffered session events |
| | `next-actions` | `2h` | Next-action prediction cache |
| | `risk` | `15m` | Risk profile cache |
| | `live-stats` | `24h` | Live stats snapshot |
| | `dashboard` | `15m` | Dashboard snapshot cache |
| | `forecast` | `24h` | Forecast snapshot cache |
| `app.rules` | `unusual-hour.start/end` | `2`/`4` | Suspicious hour window (UTC) |
| | `repeated-fail.consecutive` | `3` | KO threshold |
| | `rapid-fire.min-events` | `3` | Burst detection |
| | `rapid-fire.window-seconds` | `2` | Burst window |
| | `session-timeout.threshold-seconds` | `1200` | Inactivity timeout (20min) |
| | `impossible-seq.min-probability` | `0.005` | Markov impossible threshold |
| | `path-deviation.min-probability` | `0.02` | Markov deviation threshold |
| `app.risk` | `medium-threshold` | `0.05` | 5% anomaly rate → MEDIUM |
| | `high-threshold` | `0.20` | 20% anomaly rate → HIGH |
| | `critical-threshold` | `80.0` | Risk score ≥ 80 → CRITICAL |
| `app.scheduling` | `live-stats-fixed-rate-ms` | `5000` | Stats refresh interval |
| | `trend-cron` | `0/30 * * * * *` | Forecast refresh every 30s |
| `app.features` | `download-window-seconds` | `120` | Sliding download window |
| | `rapid-action-seconds` | `7` | Rapid action threshold |
| | `session-alert-risk-threshold` | `60.0` | Alert publish threshold |

---

## Database Schema

Managed via Liquibase changelog (`db.changelog-master.yaml`).

### `session_analysis`
Core analysis output — one row per session after inference:

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGINT PK | Auto-generated |
| `session_id` | VARCHAR(100) | Session identifier |
| `insured_id` | VARCHAR(100) | User identifier |
| `persona` | VARCHAR(50) | Behavioral persona |
| `country_code` | VARCHAR(10) | Country |
| `city` | VARCHAR(100) | City |
| `month` | VARCHAR(10) | Month string |
| `session_number` | INT | User's session index |
| `start_time` / `end_time` | DATETIME2 | Session boundaries |
| `start_hour` / `end_hour` | INT | Hour of day |
| `day_of_week` | INT | 0=Monday |
| `is_weekend` | BIT | Weekend flag |
| `first_action` / `last_action` | VARCHAR(200) | Action boundary |
| `first_route` / `last_route` | VARCHAR(200) | Route boundary |
| `total_events` | INT | Event count |
| `session_duration_seconds` | BIGINT | Duration |
| `avg/min/max_inter_action_seconds` | FLOAT | Inter-event timing |
| `unique_actions/routes/ips/devices` | INT | Distinct counts |
| `total_kos/oks` | INT | Status counts |
| `longest_ko_streak` | INT | Max consecutive KOs |
| `has_login/logout` | BIT | Login/logout presence |
| `ip_changed` / `device_changed` | BIT | Change flags |
| `total_download_actions` | INT | Download count |
| `max_downloads_in_2_minutes` | INT | Download peak |
| `ping_pong_count` | INT | Navigation loops |
| `risk_score_max/avg` | FLOAT | Per-event risk stats |
| `ended_abruptly` | BIT | No-logout indicator |
| `anomaly_event_count` | INT | Anomalous events |
| `primary_anomaly_type` | VARCHAR(50) | Dominant anomaly |
| `is_anomaly` | BIT | Binary anomaly flag |
| `anomaly_type` | VARCHAR(50) | Classified type |
| `iso_score` | FLOAT | Isolation Forest score |
| `anomaly_probability` | FLOAT | XGBoost probability |
| `anomaly_score` | FLOAT | General anomaly score |
| `type_confidence` | FLOAT | RF classifier confidence |
| `binary_detector_artifact` | VARCHAR(200) | Model used |
| `churn_probability` | FLOAT | Churn prediction |
| `ensemble_risk_score` | FLOAT | Composite 0–100 |
| `risk_level` | VARCHAR(20) | HIGH/MEDIUM/LOW |
| `persona_cluster` | INT | K-Means cluster |
| `path_deviation` | BIT | Markov deviation flag |
| `transition_from/to_action` | VARCHAR(200) | Deviant transition |
| `transition_probability` | FLOAT | Markov probability |
| `anomaly_types_json` | NVARCHAR(MAX) | JSON list of types |
| `action/route_sequence_json` | NVARCHAR(MAX) | JSON arrays |
| `action/route_sequence_signature` | VARCHAR(MAX) | Joined string |
| `action_counts_json` | NVARCHAR(MAX) | JSON map |
| `campaign_ids_json` | NVARCHAR(MAX) | JSON list |
| `next_actions_json` | NVARCHAR(MAX) | Top-3 predictions |
| `rare_transitions_json` | NVARCHAR(MAX) | JSON list |
| `context_tags_json` | NVARCHAR(MAX) | JSON list |
| `triggered_rules_json` | NVARCHAR(MAX) | JSON list |
| `warnings_json` | NVARCHAR(MAX) | JSON list |
| `feature_contributions_json` | NVARCHAR(MAX) | Top features JSON |
| `explainability_text` | NVARCHAR(MAX) | NL explanation |
| `created_at` | DATETIME2 | Audit timestamp |

### `anomaly_events`
Durable alert records — one row per alert published:

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGINT PK | Auto-generated |
| `insured_id` / `session_id` / `event_id` | VARCHAR | Identifiers |
| `event_time` | DATETIME2 | Original event time |
| `detected_at` | DATETIME2 | Inference time |
| `anomaly_tier` | VARCHAR(20) | ANOMALY / SYSTEM / RULE |
| `anomaly_type` | VARCHAR(50) | Classified type |
| `anomaly_flag` | BIT | Binary flag |
| `anomaly_score` / `anomaly_probability` | FLOAT | Detection outputs |
| `type_confidence` | FLOAT | Classifier confidence |
| `rule_type` | VARCHAR(100) | Triggered rule name |
| `churn_probability` | FLOAT | Churn score |
| `risk_score` | FLOAT | Ensemble score |
| `persona_cluster` | INT | Cluster label |
| `path_deviation` | BIT | Markov flag |
| `transition_probability` | FLOAT | Markov probability |
| `transition_from/to_action` | VARCHAR | Markov transition |
| `model_artifact` | VARCHAR(200) | Model variant used |
| `next_actions_json` | NVARCHAR(MAX) | Top-3 predictions |
| `event_json` | NVARCHAR(MAX) | Original event context |

### `user_risk_profile`
Rolling 30-day risk profile per user:

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGINT PK | Auto-generated |
| `insured_id` | VARCHAR(100) | User identifier |
| `last_updated` | DATETIME2 | Profile refresh time |
| `anomaly_count_7d/30d` | INT | Anomaly counts |
| `last_anomaly_type` | VARCHAR(50) | Most recent type |
| `risk_tier` | VARCHAR(20) | HIGH/MEDIUM/LOW |
| `anomaly_rate_30d` | FLOAT | Anomalies / sessions |
| `sessions_7d/30d` | INT | Session counts |
| `most_frequent_action_30d` | VARCHAR(200) | Dominant action |
| `avg_session_duration_30d` | FLOAT | Mean duration |
| `consecutive_clean_sessions` | INT | Clean streak |

### `next_action_prediction`
Latest Markov next-action snapshot per user:

| Column | Type | Description |
|--------|------|-------------|
| `id` | BIGINT PK | Auto-generated |
| `insured_id` | VARCHAR(100) | User identifier |
| `session_id` | VARCHAR(100) | Current session |
| `predicted_at` | DATETIME2 | Prediction time |
| `top3_actions_json` | NVARCHAR(MAX) | Top-3 action probabilities |

---

## Redis Key Layout

Keys shared with the API service (`com.noveocare.dataprocessor.config.CacheKeys`).

| Pattern | TTL | Description |
|---------|-----|-------------|
| `session:buffer:{sessionId}` | 30m | Raw event buffer per session |
| `session:insight:{insuredId}:{sessionId}` | 2h | Enriched session insight |
| `risk:{insuredId}` | 15m | User risk profile |
| `next_actions:{insuredId}` | 2h | Next-action prediction |
| `anomaly:active:{insuredId}` | 30m | Active anomaly flag |
| `stats:live:{date}` | 24h | Live stats snapshot |
| `stats:trend:{date}` | 24h | Trend/forecast snapshot |
| `stats:events:day:{date}` | 24h | Daily event counter |
| `stats:events:minute:{minute}` | 24h | Minute event counter |
| `stats:alerts:minute:{minute}` | 24h | Minute alert counter |
| `stats:actions:minute:{minute}` | 24h | Minute action hash |
| `stats:countries:minute:{minute}` | 24h | Minute country hash |
| `stats:ko:minute:{minute}` | 24h | Minute KO hash |
| `stats:downloads:day:{date}` | 24h | Daily download counter |
| `stats:downloads:minute:{minute}` | 24h | Minute download counter |
| `dashboard:{view}` | 15m | Dashboard snapshot |
| `pending:alerts:{sessionId}` | 30m | Dedup guard |
| `session:insight:index` | — (set) | Active insight key index |
| `session:insight:index:{insuredId}` | — (set) | Per-user insight index |

---

## Development Setup

### Prerequisites

- **Java 25** (JDK 25+)
- **Maven 3.9+** (wrapped via `mvnw`)
- **Docker** (for SQL Server + Kafka + Redis + Vault)
- **SQL Server** (or Docker container)
- **Apache Kafka** (or Docker container)
- **Redis** (or Docker container)

### Environment Variables (.env)

Copy `.env.example` to `.env` and configure:

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_URL` | `jdbc:sqlserver://localhost:1433;databaseName=NoveoCareDB;encrypt=true;trustServerCertificate=true` | SQL Server JDBC URL |
| `DB_USERNAME` | `app_user` | DB user |
| `DB_PASSWORD` | `securePass123!` | DB password |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker |
| `KAFKA_CONSUMER_GROUP_ID` | `data-processor-group` | Consumer group |
| `KAFKA_CONSUMER_CONCURRENCY` | `5` | Listener threads |
| `AUDIT_TRAIL_TOPIC` | `topic-audit-trail` | Input topic |
| `ANOMALY_ALERTS_TOPIC` | `topic-anomaly-alerts` | Output topic |
| `AUDIT_TRAIL_DLQ_TOPIC` | `topic-audit-trail-dlq` | DLQ topic |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |

### Infrastructure (Docker Compose)

```bash
docker compose -f docker/docker-compose.vault.yaml up -d
```

This starts SQL Server, Kafka, Redis, and Vault (if configured). The `docker-compose.vault.yaml` includes infrastructure services only.

### Build & Run

```bash
# Compile
./mvnw compile

# Run tests
./mvnw test

# Run integration tests (requires Docker infrastructure)
./mvnw verify

# Run application
./mvnw spring-boot:run
```

### Live Simulation (Kafka data feed)

```bash
# From the AI resources directory
cd src/main/resources/AI

# Full-year replay at accelerated speed
python simulator_yearly_dataset_v3.py --output-dir ./output

# Live Kafka replay
python simulator_final_revised.py --kafka-bootstrap localhost:9092 --topic topic-audit-trail --speed 60

# Anomaly-focused emulation
python anomaly_kafka_producer.py --kafka-bootstrap localhost:9092 --topic topic-audit-trail --speed 10
```

---

## Testing

| Test Class | Type | Framework |
|------------|------|-----------|
| `FeatureEngineeringServiceTest` | Unit | JUnit 5 |
| `RuntimeArtifactServiceTest` | Unit | JUnit 5 |
| `TransitionMatrixServiceTest` | Unit | JUnit 5 |
| `DashboardSnapshotServiceTest` | Unit | JUnit 5 |
| `AnomalyAlertMapperTest` | Unit | JUnit 5 (MapStruct) |
| `*IT.java` | Integration | JUnit 5 + Testcontainers + failsafe-plugin |

Integration tests use **Testcontainers** to spin up MSSQL Server containers automatically.

```bash
# Unit tests
./mvnw test

# Integration tests
./mvnw verify

# Skip tests
./mvnw package -DskipTests
```

---

## Docker Build

The project uses the **Jib Maven Plugin** for containerization (no Dockerfile needed):

```bash
# Build Docker image
./mvnw compile jib:dockerBuild

# The image will be tagged as: org.example/Data-Processor:0.0.1-SNAPSHOT
# Base image: eclipse-temurin:25-jre
# JVM flags: -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
```

---

## Notes & Known Limitations

1. **ONNX model equivalence:** `skl2onnx` exports for Isolation Forest, Random Forest, and K-Means may have subtle behavioral differences from their scikit-learn counterparts, particularly for `decision_function` values and probability calibration. Model validation after export is recommended.

2. **First-order Markov only:** Path deviation detection considers only single-step transitions. Multi-step attack patterns (A→B→C where A→B and B→C are individually normal but A→C never occurs) are not detected.

3. **No live Prophet re-forecasting:** Prophet model JSON files are loaded for metadata display only. The system relies on pre-computed CSV forecasts that degrade over time without retraining.

4. **No model versioning:** ONNX files are overwritten during training. No A/B testing, canary deployment, or rollback mechanism exists for model updates.

5. **No data drift monitoring:** Feature distributions and model performance are not monitored for decay over time.

6. **Synthetic training data:** All models are trained on synthetically generated data (`simulator_yearly_dataset_v3.py`). Real-world performance may vary and should be validated with production data before deployment.

---

## Key Java Files

| Package | File | Purpose |
|---------|------|---------|
| `inference/` | `ModelInferenceService.java` | Core ONNX ML inference orchestrator (687 lines) |
| `inference/` | `TransitionMatrixService.java` | Markov chain path deviation + next-action (115 lines) |
| `ai/` | `FeatureEngineeringService.java` | Feature extraction and tabular vector construction (489 lines) |
| `ai/` | `RuntimeArtifactService.java` | AI artifact loader at startup (483 lines) |
| `ai/` | `TextNormalization.java` | Mojibake repair + string normalization (67 lines) |
| `kafka/` | `AuditTrailConsumer.java` | Kafka event consumer with full pipeline orchestration (468 lines) |
| `service/` | `StatisticsService.java` | Real-time stats tracking + risk profile updates (433 lines) |
| `service/` | `DashboardSnapshotService.java` | Dashboard snapshot management + forecast alignment (593 lines) |
| `service/` | `TrendPredictionScheduler.java` | Prophet forecast refresh + system traffic anomaly eval (121 lines) |
| `config/` | `CacheKeys.java` | Redis key layout shared with API service (102 lines) |