package com.noveocare.dataprocessor.ai;

import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureEngineeringService {

    private final FeatureConfigLoader featureConfigLoader;
    private final DeltaScalerLoader deltaScalerLoader;
    private final VocabService vocabService;
    private final FeatureEngineeringProperties featureEngineeringProperties;

    public float[][] buildFeatureMatrix(List<AuditTrailEvent> sessionEvents) {
        FeatureConfig config = featureConfigLoader.getFeatureConfig();
        int seqLen = config.getSeqLen();
        int nFeatures = config.getNFeatures();
        float[][] matrix = new float[seqLen][nFeatures];

        if (sessionEvents == null || sessionEvents.isEmpty()) {
            return matrix;
        }

        List<AuditTrailEvent> ordered = new ArrayList<>(sessionEvents);
        ordered.sort(eventComparator());

        int sessionLength = resolveSessionLength(ordered);
        List<float[]> rows = buildRows(ordered, sessionLength, config);

        int start = Math.max(0, seqLen - rows.size());
        int copyStart = Math.max(0, rows.size() - seqLen);
        int targetIndex = start;
        for (int i = copyStart; i < rows.size(); i++) {
            matrix[targetIndex++] = rows.get(i);
        }
        return matrix;
    }

    private List<float[]> buildRows(List<AuditTrailEvent> ordered, int sessionLength, FeatureConfig config) {
        List<float[]> rows = new ArrayList<>(ordered.size());
        DeltaScaler scaler = deltaScalerLoader.getDeltaScaler();
        double mean = scaler.meanValue();
        double scale = scaler.scaleValue();

        Instant prevTime = null;
        Integer prevActionId = null;
        String prevIp = null;
        int koCount = 0;
        boolean hadIpChange = false;
        double minDeltaSoFar = Double.MAX_VALUE;

        for (int index = 0; index < ordered.size(); index++) {
            AuditTrailEvent event = ordered.get(index);
            int sequence = resolveSequence(event, index);

            Instant currentTime = event.getCreatedAt();
            double deltaSeconds = 0.0;
            if (prevTime != null && currentTime != null) {
                deltaSeconds = Duration.between(prevTime, currentTime).toMillis() / 1000.0;
                if (deltaSeconds < 0) {
                    deltaSeconds = 0.0;
                }
            }
            double deltaClipped = Math.min(deltaSeconds, featureEngineeringProperties.getDeltaClipSeconds());
            double deltaScaled = (deltaClipped - mean) / scale;

            int actionId = vocabService.actionId(event.getAction());
            int deviceId = vocabService.deviceId(event.getDevice());
            int countryId = vocabService.countryId(event.getCountryCode());
            int typeId = vocabService.typeId(event.getType());
            int subtypeId = vocabService.subtypeId(event.getSubType());

            int prevActionFeature = prevActionId == null ? 0 : prevActionId + 1;

            ZonedDateTime time = currentTime == null
                    ? ZonedDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC)
                    : ZonedDateTime.ofInstant(currentTime, ZoneOffset.UTC);
            int hour = time.getHour();
            int dayOfWeek = (time.getDayOfWeek().getValue() + 6) % 7;
            int month = time.getMonthValue();

            float hourSin = (float) Math.sin(2 * Math.PI * hour / 24.0);
            float hourCos = (float) Math.cos(2 * Math.PI * hour / 24.0);
            float dowSin = (float) Math.sin(2 * Math.PI * dayOfWeek / 7.0);
            float dowCos = (float) Math.cos(2 * Math.PI * dayOfWeek / 7.0);

            boolean isOk = "OK".equalsIgnoreCase(event.getStatus());
            boolean isKo = "KO".equalsIgnoreCase(event.getStatus());

            float seqPosNorm = sessionLength > 0 ? (float) sequence / (float) sessionLength : 0.0f;
            float isSessionStart = sequence == 1 ? 1.0f : 0.0f;
            float isSessionEnd = sequence == sessionLength ? 1.0f : 0.0f;
            float sessionLenNorm = (float) sessionLength / (float) config.getMaxSessionLen();

            float koCountSoFar = (float) koCount;
            if (isKo) {
                koCount++;
            }

            boolean ipChanged = prevIp != null && event.getIp() != null && !event.getIp().equals(prevIp);
            if (ipChanged) {
                hadIpChange = true;
            }
            float ipChangedFlag = ipChanged ? 1.0f : 0.0f;
            float hadIpChangeFlag = hadIpChange ? 1.0f : 0.0f;

            float minDeltaSoFarValue;
            if (prevTime == null) {
                minDeltaSoFarValue = (float) deltaClipped;
            } else {
                minDeltaSoFarValue = (float) minDeltaSoFar;
            }
            minDeltaSoFar = Math.min(minDeltaSoFar, deltaClipped);

            Map<String, Float> values = new HashMap<>();
            values.put("action_id", (float) actionId);
            values.put("device_id", (float) deviceId);
            values.put("country_id", (float) countryId);
            values.put("type_id", (float) typeId);
            values.put("subtype_id", (float) subtypeId);
            values.put("prev_action_id", (float) prevActionFeature);
            values.put("hour_sin", hourSin);
            values.put("hour_cos", hourCos);
            values.put("dow_sin", dowSin);
            values.put("dow_cos", dowCos);
            values.put("month_num", (float) month);
            values.put("delta_scaled", (float) deltaScaled);
            values.put("is_ok", isOk ? 1.0f : 0.0f);
            values.put("is_ko", isKo ? 1.0f : 0.0f);
            values.put("seq_pos_norm", seqPosNorm);
            values.put("is_session_start", isSessionStart);
            values.put("is_session_end", isSessionEnd);
            values.put("session_len_norm", sessionLenNorm);
            values.put("ko_count_so_far", koCountSoFar);
            values.put("ip_changed", ipChangedFlag);
            values.put("had_ip_change", hadIpChangeFlag);
            values.put("min_delta_so_far", minDeltaSoFarValue);

            float[] row = new float[config.getFeatureCols().size()];
            for (int i = 0; i < config.getFeatureCols().size(); i++) {
                row[i] = values.getOrDefault(config.getFeatureCols().get(i), 0.0f);
            }
            rows.add(row);

            prevTime = currentTime;
            prevActionId = actionId;
            prevIp = event.getIp();
        }

        return rows;
    }

    private int resolveSessionLength(List<AuditTrailEvent> ordered) {
        int maxSequence = 0;
        int maxLengthField = 0;
        for (AuditTrailEvent event : ordered) {
            if (event.getSequenceInSession() != null) {
                maxSequence = Math.max(maxSequence, event.getSequenceInSession());
            }
            if (event.getSessionLength() != null) {
                maxLengthField = Math.max(maxLengthField, event.getSessionLength());
            }
        }
        int length = Math.max(maxSequence, maxLengthField);
        if (length <= 0) {
            length = ordered.size();
        }
        if (length <= 0) {
            length = 1;
        }
        return length;
    }

    private int resolveSequence(AuditTrailEvent event, int index) {
        if (event.getSequenceInSession() != null && event.getSequenceInSession() > 0) {
            return event.getSequenceInSession();
        }
        return index + 1;
    }

    private Comparator<AuditTrailEvent> eventComparator() {
        return Comparator
                .comparing(AuditTrailEvent::getSequenceInSession, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AuditTrailEvent::getCreatedAt, Comparator.nullsLast(Instant::compareTo));
    }
}
