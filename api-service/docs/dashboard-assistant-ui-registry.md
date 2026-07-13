# Dashboard Assistant UI Registry Consistency

This document describes the complete set of backend route/element IDs and their corresponding frontend DOM IDs. Use this to verify consistency between the two projects.

## Route ID Mapping

| Backend ID | Frontend Route Name | Frontend Path | Nav Item ID |
|---|---|---|---|
| `security-overview` | `SecurityOverviewPage` | `/security-overview` | `security-overview` |
| `alerts` | `AlertsPage` | `/alerts` | `alerts` |
| `alert-investigation` | `AlertInvestigationPage` | `/alerts/:eventId` | — |
| `user-360` | `User360DetailPage` | `/users/:insuredId/360` | `user360` |
| `runtime-health` | `RuntimeHealthPage` | `/runtime` | `runtime` |
| `runtime` (alias) | `RuntimeHealthPage` | `/runtime` | `runtime` |
| `churn` | `ChurnPage` | `/churn` | `churn` |
| `forecast` | `ForecastPage` | `/forecast` | `forecast` |
| `account` | `AccountPage` | `/account` | — |

## Element ID Checklist

Backend registry entries that require matching `id` attributes in frontend HTML.

### Nav Items (MainLayout.vue)

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `nav-security-overview` | `id="nav-security-overview"` on `<q-item>` (added via `:id="'nav-' + item.id"`) | Done |
| `nav-alerts` | `id="nav-alerts"` on `<q-item>` | Done |
| `nav-user360` | `id="nav-user360"` on `<q-item>` | Done |
| `nav-runtime` | `id="nav-runtime"` on `<q-item>` | Done |
| `nav-churn` | `id="nav-churn"` on `<q-item>` | Done |
| `nav-forecast` | `id="nav-forecast"` on `<q-item>` | Done |

### AlertInvestigationPage.vue

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `btn-explain-ai` | `id="btn-explain-ai"` on `<span>` wrapping `<ai-explain-button>` | Done (pre-existing) |
| `card-event-metadata` | `id="card-event-metadata"` on `<article>` | Done (pre-existing) |
| `card-model-scores` | `id="card-model-scores"` on `<article>` | Done (pre-existing) |
| `card-sequence-evidence` | `id="card-sequence-evidence"` on `<article>` | Done (pre-existing) |
| `card-anomaly-attribution` | `id="card-anomaly-attribution"` on `<article>` | Done (pre-existing) |
| `card-session-lifecycle` | `id="card-session-lifecycle"` on `<article>` | Done (pre-existing) |
| `card-next-event-prediction` | `id="card-next-event-prediction"` on `<article>` | Done (pre-existing) |
| `card-user-business-context` | `id="card-user-business-context"` on `<article>` | Done (pre-existing) |
| `btn-evidence-payload` | `id="btn-evidence-payload"` needed on `<q-btn>` | MISSING — not in template |
| `card-model-contributions` | Not in frontend template | MISSING — not a separate card |

### AlertsPage.vue

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `alerts-table` | `id="alerts-table"` on `<div class="neo-table-wrapper">` | Done |
| `alerts-refresh-button` | `id="alerts-refresh-button"` on `<q-btn>` | Done |
| `alerts-risk-filter` | `id="alerts-risk-filter"` on `<div class="neo-v36-filters">` | Done |

### User360Page.vue

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `user360-search-input` | `id="user360-search-input"` on lookup div | Done |
| `card-user360-persona` | No matching id | MISSING — not yet added |
| `card-user360-churn` | No matching id | MISSING — not yet added |
| `card-user360-risk-summary` | No matching id | MISSING — not yet added |
| `card-user360-baseline` | No matching id | MISSING — not yet added |
| `card-user360-recent-sessions` | No matching id | MISSING — not yet added |
| `card-user360-risk-timeline` | No matching id | MISSING — not yet added |
| `card-user360-next-event-prediction` | No matching id | MISSING — not yet added |

### SecurityOverviewPage.vue

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `overview-kpi-*` | `:id="'overview-kpi-' + metric.key"` on `<article>` | Done |
| `overview-top-anomalies` | `id="overview-top-anomalies"` on `<article>` | Done |
| `overview-top-rules` | `id="overview-top-rules"` on `<article>` | Done |
| `overview-model-health` | No matching id | MISSING — not yet added |
| `overview-critical-preview` | No matching id | MISSING — not yet added |

### RuntimeHealthPage.vue

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `runtime-refresh-button` | `id="runtime-refresh-button"` on `<q-btn>` | Done |
| `card-runtime-summary` | `id="card-runtime-summary"` on first KPI `<article>` | Done |
| `card-model-health` | `id="card-model-health"` on model health panel | Done |
| `card-kafka-health` | `id="card-kafka-health"` on kafka panel | Done |

### ChurnPage.vue

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `churn-refresh-button` | `id="churn-refresh-button"` on `<q-btn>` | Done |
| `churn-risk-filter` | `id="churn-risk-filter"` on filters div | Done |
| `churn-table` | `id="churn-table"` on table wrapper | Done |

### ForecastPage.vue

| Backend Element ID | Frontend HTML id | Status |
|---|---|---|
| `forecast-refresh-button` | `id="forecast-refresh-button"` on `<q-btn>` | Done |
| `forecast-chart` | `id="forecast-chart"` on first chart panel | Done |

## Manual Verification Checklist

- [ ] `GET /api/v1/dashboard-assistant/capabilities` returns all routes, elements, panels
- [ ] `POST /api/v1/dashboard-assistant/message` with "hello" returns greeting NO_ACTION
- [ ] "show me where should i click to go to alerts page" returns HIGHLIGHT_ELEMENT nav-alerts
- [ ] "take me to runtime health" returns NAVIGATE runtime-health
- [ ] "where can i see next event prediction" on alert-investigation returns HIGHLIGHT card-next-event-prediction
- [ ] "highlight where to click to explain with AI" on alert-investigation returns HIGHLIGHT btn-explain-ai
- [ ] "take me to the view that i can see kafka and models health" returns NAVIGATE runtime-health
- [ ] "refresh the forecast" returns REFRESH_VIEW forecast
- [ ] "filter churn table to medium risk users" returns SET_FILTER churn-risk MEDIUM
- [ ] "delete all alerts" returns assistant_invalid_command_type warning
- [ ] All nav items (`nav-*`) are visible and highlighted by driver.js
