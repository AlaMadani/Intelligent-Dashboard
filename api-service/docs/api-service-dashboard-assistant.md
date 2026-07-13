# Dashboard Assistant

## Architecture

```
Quasar frontend
  -> POST /api/v1/dashboard-assistant/message
api-service
  -> NVIDIA NIM (Nemotron) OR deterministic fallback
api-service
  -> validates/normalizes response (command allowlisting)
Quasar frontend
  -> executes safe commands
```

The backend **never** exposes NVIDIA API keys to the frontend. The backend **never** executes UI actions directly. The backend returns only validated, allowlisted command DTOs.

## Config

Located in `application.yaml` under `app.assistant.dashboard.*`:

| Property | Default | Environment variable |
|---|---|---|
| `enabled` | `true` | `ASSISTANT_DASHBOARD_ENABLED` |
| `provider` | `nvidia-nim` | — |
| `model` | `nvidia/nemotron-3-super-120b-a12b` | `ASSISTANT_DASHBOARD_MODEL` |
| `temperature` | `0.0` | `ASSISTANT_DASHBOARD_TEMPERATURE` |
| `top-p` | `0.9` | `ASSISTANT_DASHBOARD_TOP_P` |
| `max-tokens` | `800` | `ASSISTANT_DASHBOARD_MAX_TOKENS` |
| `timeout-ms` | `15000` | `ASSISTANT_DASHBOARD_TIMEOUT_MS` |

Reuses the existing NVIDIA NIM API key and base URL from `app.llm.nvidia.*`.

## Flow

1. Deterministic fallback runs **first** (fast path, no model call)
2. If no deterministic match → model called
3. If model returns empty commands or throws → deterministic fallback runs again as final fallback
4. If still no match → `assistant_response_parse_failed` warning returned

## Endpoints

### `POST /api/v1/dashboard-assistant/message`

Request:

```json
{
  "message": "Where can I see next event prediction?",
  "currentRoute": "alert-investigation",
  "currentContext": {
    "eventId": "anom-000000001179",
    "insuredId": "insured-anom-00052",
    "sessionId": "sess-..."
  }
}
```

Response:

```json
{
  "message": "I'll highlight the Next Event Prediction card.",
  "commands": [
    {
      "type": "HIGHLIGHT_ELEMENT",
      "elementId": "card-next-event-prediction",
      "message": "This card shows next-event predictions and deviation evidence when available."
    }
  ],
  "requiresConfirmation": false,
  "warnings": []
}
```

When disabled:

```json
{
  "message": "Dashboard assistant is currently disabled.",
  "commands": [],
  "requiresConfirmation": false,
  "warnings": ["dashboard_assistant_disabled"]
}
```

When deterministic fallback unavailable and model fails:

```json
{
  "message": "I could not safely understand that request. Please rephrase it.",
  "commands": [],
  "requiresConfirmation": false,
  "warnings": ["assistant_response_parse_failed"]
}
```

### `GET /api/v1/dashboard-assistant/capabilities`

Returns the full capability manifest for debugging:

```json
{
  "data": {
    "commands": ["NAVIGATE", "HIGHLIGHT_ELEMENT", "SEARCH_ALERT", ...],
    "routes": ["security-overview", "alerts", ...],
    "elements": ["btn-explain-ai", "nav-alerts", ...],
    "panels": ["advanced-context", "evidence-payload", "explain-ai"]
  }
}
```

## Allowed command types

| Type | Fields | Purpose |
|---|---|---|
| `NAVIGATE` | `routeName`, `params` (optional) | Navigate to a dashboard page |
| `HIGHLIGHT_ELEMENT` | `elementId`, `message` | Highlight a UI element |
| `SEARCH_ALERT` | `query`, `message` | Search for an alert |
| `SEARCH_USER` | `query`, `message` | Search for a user |
| `SEARCH_SESSION` | `query`, `message` | Search for a session |
| `OPEN_PANEL` | `panelId`, `message` | Open a panel/section |
| `SET_FILTER` | `target`, `value`, `message` | Apply a filter (churn-risk, alerts-risk) |
| `REFRESH_VIEW` | `target`, `message` | Refresh page data (forecast, alerts, churn, runtime) |
| `NO_ACTION` | `message` | No action needed |

## Allowed routes

| Route name | Description | Page component |
|---|---|---|
| `security-overview` | Security overview dashboard | `SecurityOverviewPage` |
| `alerts` | Live alerts list | `AlertsPage` |
| `alert-investigation` | Alert investigation details | `AlertInvestigationPage` |
| `user-360` | User 360 profile and history | `User360Page` / `User360DetailPage` |
| `runtime-health` | Runtime health monitoring | `RuntimeHealthPage` |
| `runtime` | Runtime health (alias) | `RuntimeHealthPage` |
| `churn` | Churn risk dashboard | `ChurnPage` |
| `forecast` | Forecast dashboard | `ForecastPage` |
| `account` | Account settings | `AccountPage` |

## Allowed nav elements (sidebar)

| Element ID | Route | Description |
|---|---|---|
| `nav-security-overview` | all | Security overview nav item |
| `nav-alerts` | all | Alerts nav item |
| `nav-user360` | all | User 360 nav item |
| `nav-runtime` | all | Runtime health nav item |
| `nav-churn` | all | Churn nav item |
| `nav-forecast` | all | Forecast nav item |

## Allowed page elements

### Alert Investigation (`alert-investigation`)

| Element ID | Description |
|---|---|
| `btn-explain-ai` | Explain with AI button |
| `btn-evidence-payload` | Evidence payload button |
| `card-event-metadata` | Event metadata card |
| `card-model-scores` | Model scores card |
| `card-sequence-evidence` | Sequence evidence card |
| `card-next-event-prediction` | Next event prediction card |
| `card-session-lifecycle` | Session lifecycle card |
| `card-anomaly-attribution` | Anomaly type attribution card |
| `card-user-business-context` | User business context card |

### Alerts (`alerts`)

| Element ID | Description |
|---|---|
| `alerts-refresh-button` | Refresh alerts data button |
| `alerts-risk-filter` | Risk level filter |
| `alerts-table` | Live alerts table |

### User 360 (`user-360`)

| Element ID | Description |
|---|---|
| `user360-search-input` | Insured ID search input |
| `card-user360-persona` | User persona card |
| `card-user360-churn` | User churn probability card |
| `card-user360-risk-summary` | User risk summary card |
| `card-user360-baseline` | User baseline card |
| `card-user360-recent-sessions` | User recent sessions card |
| `card-user360-risk-timeline` | User risk timeline card |
| `card-user360-next-event-prediction` | Next event prediction card |

### Security Overview (`security-overview`)

| Element ID | Description |
|---|---|
| `overview-refresh-button` | Refresh overview data button |
| `overview-kpi-*` | KPI metric cards (dynamic per metric key) |
| `overview-top-anomalies` | Top anomaly types chart |
| `overview-top-rules` | Top triggered rules chart |
| `overview-model-health` | Model health panel |
| `overview-critical-preview` | Critical alerts preview table |

### Runtime Health (`runtime-health`)

| Element ID | Description |
|---|---|
| `runtime-refresh-button` | Refresh runtime health button |
| `card-runtime-summary` | Overall status card |
| `card-model-health` | Model health card |
| `card-kafka-health` | Kafka health card |

### Churn (`churn`)

| Element ID | Description |
|---|---|
| `churn-refresh-button` | Refresh churn data button |
| `churn-risk-filter` | Risk level filter |
| `churn-table` | Churn users table |

### Forecast (`forecast`)

| Element ID | Description |
|---|---|
| `forecast-refresh-button` | Refresh forecast data button |
| `forecast-chart` | Forecast chart |

## Allowed panels

| Panel ID | Description |
|---|---|
| `advanced-context` | Advanced context panel |
| `evidence-payload` | Evidence payload panel |
| `explain-ai` | AI explanation panel |

## Deterministic fallback intents

The service tries these matchers before calling the model:

| Intent | Triggers | Output |
|---|---|---|
| Greeting | "hello", "hi there", "hey", "good morning" | NO_ACTION with greeting message |
| Prediction highlight | "next event prediction", "prediction card", "deviation evidence" | HIGHLIGHT card-next-event-prediction (navigates first if needed) |
| Explain AI | "explain with ai", "explain ai" | HIGHLIGHT btn-explain-ai (navigates first if needed) |
| Runtime health | "kafka health", "model health" + seeking keywords | NAVIGATE + HIGHLIGHT kafka/model cards |
| User prediction | "next event prediction for a user" | NAVIGATE user-360 + HIGHLIGHT card-user360-next-event-prediction |
| Alerts highlight | seeking keywords + "alert" | HIGHLIGHT nav-alerts |
| Forecast highlight | seeking keywords + "forecast" | HIGHLIGHT forecast-chart or NAVIGATE forecast |
| Filter | "filter" + churn/alert + risk level | SET_FILTER churn-risk or alerts-risk |
| Refresh | "refresh" + page name | REFRESH_VIEW with target |
| Navigation | "go to / take me to / open / show" + route | NAVIGATE with route name |

## Security model

- No destructive actions
- No write/delete/rerun actions
- No arbitrary URL navigation
- No arbitrary CSS selectors
- No arbitrary JavaScript
- Only read/navigation/search/highlight/open-panel/filter/refresh commands
- All command types, route names, element IDs, and panel IDs are validated against the allowlist
- Unknown values are removed with appropriate warnings:
  - `assistant_command_rejected`
  - `assistant_unknown_route`
  - `assistant_unknown_element`
  - `assistant_unknown_panel`
  - `assistant_invalid_command_type`

## Frontend responsibilities

- Execute only allowlisted commands
- Handle `NO_ACTION` gracefully
- Display warnings to the user
- Provide `currentRoute` and `currentContext` from the router/store
- Never forward raw model JSON to the UI
- Support `SET_FILTER` via window CustomEvent dispatch
- Support `REFRESH_VIEW` via document CustomEvent dispatch
- Map backend route IDs to actual Quasar route names via `ROUTE_ALIASES`

## Frontend route alias mapping

| Backend ID | Frontend Route Name |
|---|---|
| `security-overview` | `SecurityOverviewPage` |
| `alerts` | `AlertsPage` |
| `alert-investigation` | `AlertInvestigationPage` |
| `user-360` | `User360DetailPage` |
| `churn` | `ChurnPage` |
| `forecast` | `ForecastPage` |
| `runtime` / `runtime-health` | `RuntimeHealthPage` |
| `account` | `AccountPage` |

## Frontend event contracts (CustomEvents)

| Event | Detail | Listener |
|---|---|---|
| `assistant:filter-churn-risk` | `{ value: "MEDIUM" }` | ChurnPage listens via window |
| `assistant:filter-alerts-risk` | `{ value: "CRITICAL" }` | AlertsPage listens via window |
| `assistant:refresh-forecast` | — | ForecastPage listens via document |
| `assistant:refresh-alerts` | — | AlertsPage listens via document |
| `assistant:refresh-churn` | — | ChurnPage listens via document |
| `assistant:refresh-runtime` | — | RuntimeHealthPage listens via document |

## Known limitations

- Model may hallucinate route names or element IDs — backend validation rejects these
- Model may produce malformed JSON — deterministic fallback catches common requests first
- Model may suggest destructive actions — backend rejects invalid command types
- Context is limited to the current route and a small set of user-provided parameters
- Session/response history is not maintained between requests
- Filter and refresh commands require frontend event listener implementation on target pages
- Element IDs must exist in the DOM for HIGHLIGHT_ELEMENT to work (driver.js) — page components must have matching `id` attributes
