package com.noveocare.dataprocessor.ai.sequence;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveocare.dataprocessor.ai.TextNormalization;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiSequenceProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SequenceEventMapper {

    private static final double TWO_PI = 2.0 * Math.PI;
    private static final long TIME_DELTA_WARNING_MS = 1_000L;

    private final RuntimeArtifactService artifactService;
    private final AiSequenceProperties sequenceProperties;

    public SequenceEventValues map(AuditTrailEvent event, Instant previousTimestamp) {
        List<String> warnings = new ArrayList<>();
        boolean schemaValid = true;

        Map<String, String> categoricalValues = new LinkedHashMap<>();
        for (String field : artifactService.getSequenceMetadata().getCatCols()) {
            String value = rawCategorical(event, field);
            if (isBlank(value) && sequenceProperties.isAllowLegacyFallback()) {
                value = legacyCategorical(event, field);
                if (!isBlank(value)) {
                    warnings.add("legacy_fallback_" + field);
                }
            }
            if (isBlank(value)) {
                warnings.add("missing_required_field_" + field);
                if (sequenceProperties.isStrictSchema()) {
                    schemaValid = false;
                }
            }
            categoricalValues.put(field, TextNormalization.normalizeLabel(value));
        }

        Map<String, Double> continuousValues = new LinkedHashMap<>();
        for (String field : artifactService.getSequenceMetadata().getContCols()) {
            Double value = rawContinuous(event, field, previousTimestamp, warnings);
            if (value == null) {
                warnings.add("missing_required_field_" + field);
                if (sequenceProperties.isStrictSchema()) {
                    schemaValid = false;
                }
                value = 0.0;
            }
            continuousValues.put(field, value);
        }

        return SequenceEventValues.builder()
                .insuredId(event.getInsuredId())
                .sessionId(event.getSessionId())
                .eventId(event.getId())
                .timestamp(event.getCreatedAt())
                .categoricalColumns(artifactService.getSequenceMetadata().getCatCols())
                .continuousColumns(artifactService.getSequenceMetadata().getContCols())
                .categoricalValues(categoricalValues)
                .continuousValuesRaw(continuousValues)
                .schemaValid(schemaValid)
                .warnings(List.copyOf(warnings))
                .build();
    }

    private String rawCategorical(AuditTrailEvent event, String field) {
        return switch (field) {
            case "page" -> event.getPage();
            case "frontend_action_name" -> event.getFrontendActionName();
            case "api_template" -> event.getApiTemplate();
            case "action_value" -> event.getActionValue();
            case "action_type" -> event.getActionType();
            case "action_subtype" -> event.getActionSubtype();
            case "http_method" -> event.getHttpMethod();
            case "status" -> event.getStatus();
            case "device" -> event.getDevice();
            case "browser" -> event.getBrowser();
            case "os" -> event.getOs();
            case "ip_country" -> event.getIpCountry();
            case "controller" -> event.getController();
            case "api_family" -> event.getApiFamily();
            case "environment_id" -> event.getEnvironmentId();
            default -> null;
        };
    }

    private String legacyCategorical(AuditTrailEvent event, String field) {
        return switch (field) {
            case "page" -> firstNonBlank(event.getRoute(), event.getPage());
            case "frontend_action_name" -> firstNonBlank(event.getAction(), event.getActionValue());
            case "api_template" -> event.getRoute();
            case "action_value" -> firstNonBlank(event.getActionValue(), event.getAction());
            case "action_type" -> firstNonBlank(event.getActionType(), event.getType());
            case "action_subtype" -> firstNonBlank(event.getActionSubtype(), event.getSubType());
            case "ip_country" -> firstNonBlank(event.getIpCountry(), event.getCountryCode());
            default -> rawCategorical(event, field);
        };
    }

    private Double rawContinuous(AuditTrailEvent event, String field, Instant previousTimestamp, List<String> warnings) {
        return switch (field) {
            case "time_since_prev_action_ms" -> timeSincePrevious(event, previousTimestamp, warnings);
            case "request_data_size_bytes" -> requestSize(event);
            case "response_data_size_bytes" -> responseSize(event);
            case "is_business_hours" -> businessHours(event, warnings);
            case "is_weekend" -> weekend(event, warnings);
            case "hour_sin" -> Math.sin(TWO_PI * hour(event, warnings) / 24.0);
            case "hour_cos" -> Math.cos(TWO_PI * hour(event, warnings) / 24.0);
            case "dow_sin" -> Math.sin(TWO_PI * dayOfWeek(event, warnings) / 7.0);
            case "dow_cos" -> Math.cos(TWO_PI * dayOfWeek(event, warnings) / 7.0);
            default -> null;
        };
    }

    private Double timeSincePrevious(AuditTrailEvent event, Instant previousTimestamp, List<String> warnings) {
        Long provided = event.getTimeSincePrevActionMs();
        Instant current = event.getCreatedAt();
        Long computed = null;
        if (current != null) {
            computed = previousTimestamp == null ? 0L : Math.max(0L, Duration.between(previousTimestamp, current).toMillis());
        }
        if (computed != null && provided != null && Math.abs(computed - provided) > TIME_DELTA_WARNING_MS) {
            warnings.add("time_delta_mismatch");
        }
        if (computed != null) {
            return computed.doubleValue();
        }
        if (provided != null) {
            warnings.add("time_delta_from_raw_field");
            return provided.doubleValue();
        }
        return null;
    }

    private Double requestSize(AuditTrailEvent event) {
        if (event.getRequestDataSizeBytes() != null) {
            return event.getRequestDataSizeBytes().doubleValue();
        }
        if (sequenceProperties.isAllowLegacyFallback() && event.getRequestData() != null) {
            return (double) utf8Size(event.getRequestData());
        }
        return null;
    }

    private Double responseSize(AuditTrailEvent event) {
        if (event.getResponseDataSizeBytes() != null) {
            return event.getResponseDataSizeBytes().doubleValue();
        }
        if (sequenceProperties.isAllowLegacyFallback() && event.getRequestReturn() != null) {
            return (double) utf8Size(event.getRequestReturn());
        }
        return null;
    }

    private Double businessHours(AuditTrailEvent event, List<String> warnings) {
        Integer computed = event.getCreatedAt() == null ? null : (hourFromTimestamp(event) >= 8 && hourFromTimestamp(event) < 19 ? 1 : 0);
        Integer provided = event.getIsBusinessHours();
        if (computed != null && provided != null && !computed.equals(normalizeBinary(provided))) {
            warnings.add("business_hours_mismatch");
        }
        if (provided != null) {
            return (double) normalizeBinary(provided);
        }
        return computed == null ? null : computed.doubleValue();
    }

    private Double weekend(AuditTrailEvent event, List<String> warnings) {
        Integer computed = event.getCreatedAt() == null ? null : (isWeekend(event.getCreatedAt()) ? 1 : 0);
        Integer provided = event.getIsWeekend();
        if (computed != null && provided != null && !computed.equals(normalizeBinary(provided))) {
            warnings.add("weekend_mismatch");
        }
        if (provided != null) {
            return (double) normalizeBinary(provided);
        }
        return computed == null ? null : computed.doubleValue();
    }

    private int hour(AuditTrailEvent event, List<String> warnings) {
        Integer computed = event.getCreatedAt() == null ? null : hourFromTimestamp(event);
        Integer provided = event.getHour();
        if (computed != null && provided != null && !computed.equals(provided)) {
            warnings.add("hour_mismatch");
        }
        if (computed != null) {
            return computed;
        }
        return provided == null ? 0 : provided;
    }

    private int dayOfWeek(AuditTrailEvent event, List<String> warnings) {
        Integer computed = event.getCreatedAt() == null ? null : dayOfWeekFromTimestamp(event.getCreatedAt());
        Integer provided = event.getRawDayOfWeek();
        if (computed != null && provided != null && !computed.equals(provided)) {
            warnings.add("day_of_week_mismatch");
        }
        if (computed != null) {
            return computed;
        }
        return provided == null ? 0 : provided;
    }

    private int hourFromTimestamp(AuditTrailEvent event) {
        return event.getCreatedAt().atZone(ZoneOffset.UTC).getHour();
    }

    private int dayOfWeekFromTimestamp(Instant timestamp) {
        return timestamp.atZone(ZoneOffset.UTC).getDayOfWeek().getValue() - 1;
    }

    private boolean isWeekend(Instant timestamp) {
        DayOfWeek day = ZonedDateTime.ofInstant(timestamp, ZoneOffset.UTC).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private int normalizeBinary(Integer value) {
        return value != null && value != 0 ? 1 : 0;
    }

    private int utf8Size(JsonNode node) {
        return node == null ? 0 : node.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
