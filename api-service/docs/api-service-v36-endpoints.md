# API Service V3.6.1 Endpoints

`api-service` is a read-side Spring Boot API. It reads V3.6.1 Redis snapshots and SQL Server rows written by `dataprocessor`, exposes REST/SSE to the Quasar frontend, and calls Gemini only on explicit explanation requests.

It does not run ML inference, rebuild feature vectors, recompute risk, consume raw Kafka scoring events, call `dataprocessor`, or own database migrations.

## Response Envelope

All controller responses use the existing `ApiResponse<T>` envelope. Frontend code should read business fields from `response.data`, not from the response root.

Unless a response example is explicitly labeled **Full response body**, examples in this document show the `data` payload only for readability.

The actual response body shape is:

```json
{
  "data": {
    "schemaVersion": "v3.6.1"
  },
  "meta": null
}
```

## V3.6.1 Pagination

V3.6.1 list endpoints currently return pagination fields inside `data`, not in `ApiResponse.meta`.

Actual list response shape:

```json
{
  "data": {
    "schemaVersion": "v3.6.1",
    "items": [],
    "limit": 100,
    "offset": 0,
    "count": 0,
    "hasMore": false
  },
  "meta": null
}
```

The legacy endpoints such as `/api/v1/sessions` and `/api/v1/anomalies` continue to use the older `ApiResponse.meta` pagination structure.

## ID Conventions

- V3.6.1 alert endpoints use `eventId`: `GET /api/v1/alerts/{eventId}`.
- Legacy anomaly endpoints use the SQL database numeric anomaly id: `GET /api/v1/anomalies/{id}`.
- V3.6.1 live alert and investigation DTOs include `eventId` and, when the payload came from SQL or already contains it, `id` and `anomalyDbId`.
- Redis alert snapshots may not contain `id/anomalyDbId` unless `dataprocessor` writes them. SQL fallback responses do include them.
- Frontend routing rule: use `/alerts/{eventId}` for the V3.6.1 investigation view; use `/anomalies/{id}` only for legacy screens.

## Source and Warnings

Source/warning metadata is not yet a universal field on every V3.6.1 DTO, but it is available where the frontend most needs degraded/fallback banners:

- Alert list items: `source` is `redis` or `sql`; `warnings` is an array.
- Alert investigation: `source` is `redis`, `sql-payload`, or `sql`; `warnings` is an array; `runtimeWarnings` contains runtime-specific model warnings.
- Runtime health, diagnostics, reports, and forecast responses expose warning fields specific to those contracts, such as `warnings`, `fieldCoverageWarnings`, or `forecastWarnings`.

## Runtime Health

`GET /api/v1/ai/runtime-health`

Reads `ai:runtime:health:v3_6`.

```json
{
  "schemaVersion": "v3.6.1",
  "runtimeVersion": "v3.6.1",
  "status": "HEALTHY",
  "personaEnabled": false,
  "llmEvidencePayloadEnabled": true,
  "modelHealth": {
    "transformerOnnx": {
      "artifactExists": true,
      "runtimeInitialized": true,
      "lastInferenceSucceeded": true
    }
  },
  "warnings": []
}
```

Missing snapshot fallback:

```json
{
  "schemaVersion": "v3.6.1",
  "runtimeVersion": "v3.6.1",
  "status": "UNKNOWN",
  "message": "Runtime health snapshot not available"
}
```

## Security Overview

`GET /api/v1/security/overview`

Reads `dashboard:security-overview:v3_6`, then falls back to existing SQL/legacy aggregates.

```json
{
  "schemaVersion": "v3.6.1",
  "snapshotTimestamp": "2026-05-25T02:16:00Z",
  "totalEventsToday": 48200,
  "activeUsersToday": 1300,
  "anomalyRateToday": 0.018,
  "criticalAlertsToday": 7,
  "highRiskAlertsToday": 24,
  "averageRiskScoreToday": 41.5,
  "predictedAnomalyRateTomorrow": 0.019,
  "predictedTotalEventsTomorrow": 49000,
  "expectedAlertVolumeTomorrow": 931,
  "topAnomalyTypes": {"data_exfiltration": 3},
  "topTriggeredRules": {"OFF_HOURS_ACCESS": 8},
  "modelHealthSummary": {},
  "fieldCoverageWarnings": []
}
```

## Diagnostics

`GET /api/v1/security/diagnostics`

Reads runtime health, sequence/tabular field coverage, and model latency snapshots.

```json
{
  "schemaVersion": "v3.6.1",
  "runtimeHealth": {"schemaVersion": "v3.6.1", "status": "HEALTHY"},
  "fieldCoverage": {
    "sequence": {},
    "tabular": {}
  },
  "modelLatency": {},
  "fallbackMode": "normal",
  "warnings": []
}
```

## Live Alerts

`GET /api/v1/alerts/live?riskLevel=CRITICAL&limit=100`

Reads `alerts:live:v3_6`, then falls back to SQL `anomaly_events`.

**Full response body**:

```json
{
  "data": {
    "schemaVersion": "v3.6.1",
    "items": [
      {
        "schemaVersion": "v3.6.1",
        "id": 98765,
        "anomalyDbId": 98765,
        "eventId": "evt-123",
        "recordId": "evt-123",
        "insuredId": "insured-42",
        "sessionId": "session-99",
        "timestamp": "2026-05-25T02:15:00Z",
        "eventAction": "DOWNLOAD_DOCUMENT",
        "apiTemplate": "/api/documents/{id}/download",
        "apiFamily": "documents",
        "controller": "DocumentController",
        "page": "documents",
        "country": "MA",
        "device": "desktop",
        "browser": "Chrome",
        "os": "Windows",
        "httpMethod": "GET",
        "status": "200",
        "riskLevel": "CRITICAL",
        "finalRiskScore": 87.0,
        "anomalyType": "data_exfiltration",
        "anomalyTypeConfidence": 0.82,
        "xgboostAnomalyScore": 0.91,
        "xgboostAnomalyScore100": 91.0,
        "lightgbmAlertScore": 0.88,
        "lightgbmAlertScore100": 88.0,
        "transformerRiskScore100": 79.0,
        "tcnRiskScore100": 74.0,
        "ruleRiskScore": 85.0,
        "modelContributions": {
          "xgboost": 27.3,
          "lightgbm": 22.0,
          "transformer": 15.8,
          "tcn": 0.0,
          "rules": 12.8,
          "businessContext": 0.0,
          "aggregationBoost": 5.0,
          "raw": {}
        },
        "triggeredRuleCodes": ["OFF_HOURS_ACCESS", "LARGE_DOWNLOAD"],
        "churnProbability": 0.72,
        "churnRiskLevel": "HIGH",
        "personaLabel": "persona_disabled",
        "llmEvidencePayloadAvailable": true,
        "llmEvidenceRedisKey": "alert:llm-evidence:evt-123",
        "alertStatus": "OPEN",
        "createdAt": "2026-05-25T02:15:01Z",
        "source": "sql",
        "warnings": []
      }
    ],
    "limit": 100,
    "offset": 0,
    "count": 1,
    "hasMore": false
  },
  "meta": null
}
```

Optional/nullable frontend table fields include `id`, `anomalyDbId`, `controller`, `page`, `browser`, `os`, `httpMethod`, `status`, model scores, `modelContributions`, churn fields, and LLM evidence fields. Redis snapshots may omit these fields; SQL fallback fills those that exist in the stored row.

## Critical Alerts

`GET /api/v1/alerts/critical`

Reads `alerts:critical:v3_6`, then falls back to SQL alerts with risk/tier `CRITICAL`.

## Alert Investigation

`GET /api/v1/alerts/{eventId}`

Reads `alert:investigation:{eventId}`, then SQL `investigation_payload_json`, then builds a detail response from stored SQL fields.

Example below shows the `data` payload.

```json
{
  "schemaVersion": "v3.6.1",
  "id": 98765,
  "anomalyDbId": 98765,
  "eventId": "evt-123",
  "recordId": "evt-123",
  "insuredId": "insured-42",
  "sessionId": "session-99",
  "timestamp": "2026-05-25T02:15:00Z",
  "eventMetadata": {
    "eventAction": "DOWNLOAD_DOCUMENT",
    "apiTemplate": "/api/documents/{id}/download",
    "apiFamily": "documents",
    "country": "MA",
    "device": "desktop",
    "httpMethod": "GET",
    "status": "200"
  },
  "riskLevel": "CRITICAL",
  "finalRiskScore": 87.0,
  "anomalyType": "data_exfiltration",
  "anomalyTypeConfidence": 0.82,
  "triggeredRules": ["OFF_HOURS_ACCESS", "LARGE_DOWNLOAD"],
  "modelScores": {
    "xgboostAnomalyScore": 0.91,
    "xgboostAnomalyScore100": 91.0,
    "lightgbmAlertScore": 0.88,
    "lightgbmAlertScore100": 88.0,
    "transformerSurpriseScore": 3.2,
    "transformerRiskScore100": 79.0,
    "tcnRiskScore100": 74.0,
    "businessContextScore": 0.0,
    "aggregationBoost": 5.0,
    "finalRiskScore": 87.0,
    "ruleRiskScore": 85.0
  },
  "modelContributions": {
    "xgboost": 27.3,
    "lightgbm": 22.0,
    "transformer": 15.8,
    "tcn": 0.0,
    "rules": 12.8,
    "businessContext": 0.0,
    "aggregationBoost": 5.0,
    "raw": {
      "xgboost": 27.3,
      "lightgbm": 22.0,
      "transformer": 15.8,
      "rules": 12.8
    }
  },
  "sequenceEvidence": {
    "selectedSequenceModel": "transformer",
    "sequenceModelArtifact": "transformer_sequence_engine.onnx",
    "contextAvailable": true,
    "windowSize": 10,
    "sequenceCatScore": 0.42,
    "sequenceContScore": 0.31,
    "sequenceCtxScore": 0.27,
    "topSequenceSurpriseFields": [
      {"field": "eventAction", "value": "DOWNLOAD_DOCUMENT", "score": 0.77}
    ],
    "raw": {}
  },
  "tabularEvidence": {
    "featureContract": "tabular_anomaly_feature_contract.json",
    "availableModels": ["xgboost", "lightgbm", "oneclasssvm"],
    "unavailableModels": ["catboost"],
    "featureWarnings": {},
    "raw": {}
  },
  "ruleEvidence": {
    "ruleRiskScore": 85.0,
    "triggeredRules": ["OFF_HOURS_ACCESS", "LARGE_DOWNLOAD"],
    "ruleContributions": {
      "OFF_HOURS_ACCESS": 35.0,
      "LARGE_DOWNLOAD": 50.0
    }
  },
  "anomalyTypeAttribution": {
    "anomalyType": "data_exfiltration",
    "confidence": 0.82,
    "source": "hybrid_rules_models",
    "evidence": {
      "rule": "LARGE_DOWNLOAD",
      "modelAgreement": true
    }
  },
  "churnContext": {
    "probability": 0.72,
    "riskLevel": "HIGH",
    "modelName": "ExtraTrees",
    "modelArtifact": "churn_profile_only_ExtraTrees.json",
    "featureWarnings": {}
  },
  "forecastContext": {
    "predictedTotalEvents": 49000,
    "predictedAnomalyRate": 0.019,
    "expectedAlertVolume": 931,
    "forecastModelNames": {
      "totalEvents": "XGBoost",
      "anomalyRate": "Ridge"
    },
    "forecastWarnings": [],
    "raw": {}
  },
  "persona": {
    "enabled": false,
    "cluster": -1,
    "label": "persona_disabled",
    "source": "disabled_v3_6_refactor",
    "confidence": null
  },
  "runtimeWarnings": [],
  "llmEvidencePayloadAvailable": true,
  "llmEvidenceRedisKey": "alert:llm-evidence:evt-123",
  "llm": {
    "evidenceAvailable": true,
    "evidenceRedisKey": "alert:llm-evidence:evt-123",
    "evidenceEndpoint": "/api/v1/explanations/alerts/evt-123/evidence",
    "cachedExplanationEndpoint": "/api/v1/explanations/alerts/evt-123",
    "generateExplanationEndpoint": "POST /api/v1/explanations/alerts/evt-123"
  },
  "source": "sql",
  "warnings": [],
  "rawPayload": {}
}
```

## LLM Evidence

`GET /api/v1/explanations/alerts/{eventId}/evidence`

Reads `alert:llm-evidence:{eventId}`, then SQL `llm_explanation_evidence_payload_json`.

```json
{
  "schemaVersion": "v3.6.1",
  "eventId": "evt-123",
  "risk": {
    "finalRiskScore": 87.0,
    "riskLevel": "CRITICAL"
  },
  "modelScores": {
    "xgboostAnomalyScore100": 91.0,
    "lightgbmAlertScore100": 88.0
  },
  "llmInstruction": {
    "doNotInventEvidence": true
  }
}
```

## LLM Explanation

Cached-only:

`GET /api/v1/explanations/alerts/{eventId}`

Generate on demand:

`POST /api/v1/explanations/alerts/{eventId}`

```json
{
  "forceRefresh": false,
  "style": "security_analyst",
  "language": "en",
  "includeRecommendedActions": true
}
```

Response:

```json
{
  "schemaVersion": "v3.6.1",
  "eventId": "evt-123",
  "generatedAt": "2026-05-25T02:17:00Z",
  "provider": "gemini",
  "model": "gemini-2.5-flash",
  "cached": false,
  "summary": "Generated explanation text",
  "evidenceBullets": ["Risk level: CRITICAL"],
  "possibleInterpretation": "The event may align with data_exfiltration.",
  "recommendedActions": ["Open the alert investigation and review the stored model, sequence, and rule evidence."],
  "modelScoreExplanation": {"xgboostAnomalyScore100": 91.0},
  "disclaimer": "Generated from model and rule evidence. Analyst aid only."
}
```

Legacy compatibility:

`GET /api/v1/anomalies/{id}/explain` still exists. It attempts V3.6.1 evidence first, then uses the previous context-based behavior.

## User 360

`GET /api/v1/users/{insuredId}/360`

Reads `user:360:{insuredId}` with fallback to the existing user dashboard.

```json
{
  "schemaVersion": "v3.6.1",
  "insuredId": "insured-42",
  "persona": {
    "enabled": false,
    "cluster": -1,
    "label": "persona_disabled",
    "source": "disabled_v3_6_refactor"
  },
  "churn": {"probability": 0.72, "riskLevel": "HIGH"},
  "risk": {
    "averageRiskScoreLast30d": 87.0,
    "alertCountLast30d": 1,
    "criticalAlertCountLast30d": 1
  },
  "baseline": {
    "usualCountry": "MA",
    "usualActiveHours": [2],
    "topApiFamilies": []
  },
  "recentSessions": [],
  "riskTimeline": []
}
```

## User Alerts

`GET /api/v1/users/{insuredId}/alerts`

Reads `alerts:user:{insuredId}`, then falls back to SQL alert search.

## Churn Dashboard

`GET /api/v1/churn/dashboard`

Reads `dashboard:churn:v3_6`, then falls back to stored SQL churn fields.

```json
{
  "schemaVersion": "v3.6.1",
  "totalUsers": 1200,
  "highChurnRiskUsers": 55,
  "mediumChurnRiskUsers": 180,
  "lowChurnRiskUsers": 965,
  "averageChurnProbability": 0.24,
  "topChurnRiskUsers": [],
  "churnRiskDistribution": {"HIGH": 55}
}
```

`GET /api/v1/churn/users?riskLevel=HIGH&limit=50` returns stored churn-risk users.

## Forecast Dashboard

`GET /api/v1/forecast/dashboard`

Reads `dashboard:forecast:v3_6`, then falls back to legacy trend payload.

```json
{
  "schemaVersion": "v3.6.1",
  "forecastDate": "2026-05-26",
  "predictedTotalEvents": 49000,
  "predictedAnomalyRate": 0.019,
  "expectedAlertVolume": 931,
  "historicalTotalEvents": [],
  "historicalAnomalyRate": [],
  "forecastModelNames": {
    "totalEvents": "XGBoost",
    "anomalyRate": "Ridge"
  },
  "forecastWarnings": []
}
```

## Reports

`GET /api/v1/ai/final-winners`

Reads `classpath:/AI/reports/final_use_case_winners.json` when copied into api-service resources. If not available, returns an unavailable response instead of reading outside the classpath.

`GET /api/v1/ai/reports` returns report metadata only.

## SSE Refresh Events

Existing endpoint:

`GET /api/v1/stream/live`

Existing events remain:

- `stats`
- `refresh`

V3.6.1 lightweight refresh events are also emitted:

- `alerts`
- `security-overview`
- `runtime-health`
- `churn`
- `forecast`

These events are small invalidation payloads such as:

```json
{"refresh": "alerts"}
```

## Error Response

New V3.6.1 endpoints return structured errors:

```json
{
  "schemaVersion": "v3.6.1",
  "timestamp": "2026-05-25T02:17:00Z",
  "path": "/api/v1/alerts/evt-missing",
  "status": 404,
  "error": "NOT_FOUND",
  "message": "Alert not found"
}
```
