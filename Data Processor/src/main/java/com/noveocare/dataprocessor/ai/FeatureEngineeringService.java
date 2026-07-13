package com.noveocare.dataprocessor.ai;

import com.noveocare.dataprocessor.config.FeatureEngineeringProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import com.noveocare.dataprocessor.dto.SessionSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureEngineeringService {

    private static final Set<String> LOGIN_ACTIONS = Set.of("Connexion", "Connexion SSO", "Connexion en tant que");
    private static final Set<String> LOGOUT_ACTIONS = Set.of("Deconnexion", "SSO Disconnect");
    private static final String[] DOWNLOAD_WORDS = {
            "telecharg", "document", "certificat", "decompte", "wallet", "carte tp"
    };

    private final FeatureEngineeringProperties properties;

    public List<AuditTrailEvent> enrichSessionEvents(List<AuditTrailEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }

        List<AuditTrailEvent> ordered = new ArrayList<>(events);
        ordered.forEach(this::normalizeEventFields);
        ordered.sort(eventComparator());

        Instant sessionStart = ordered.get(0).getCreatedAt();
        Instant sessionEnd = ordered.get(ordered.size() - 1).getCreatedAt();
        long totalDurationSeconds = sessionStart != null && sessionEnd != null
                ? Math.max(0, Duration.between(sessionStart, sessionEnd).getSeconds())
                : 0;

        String firstIp = ordered.get(0).getIp();
        String firstDevice = ordered.get(0).getDevice();
        Set<String> seenIps = new LinkedHashSet<>();
        Set<String> seenDevices = new LinkedHashSet<>();
        int cumulativeKos = 0;
        int currentKoStreak = 0;
        int longestKoStreak = 0;
        int hasLoggedIn = 0;
        int downloadCount = 0;
        int pingPongCount = 0;
        Deque<Instant> downloadWindow = new ArrayDeque<>();

        for (int index = 0; index < ordered.size(); index++) {
            AuditTrailEvent event = ordered.get(index);
            AuditTrailEvent prev = index > 0 ? ordered.get(index - 1) : null;
            AuditTrailEvent next = index + 1 < ordered.size() ? ordered.get(index + 1) : null;
            Instant currentTime = event.getCreatedAt();
            long timeDelta = prev != null && prev.getCreatedAt() != null && currentTime != null
                    ? Math.max(0, Duration.between(prev.getCreatedAt(), currentTime).getSeconds())
                    : 0;

            if (notBlank(event.getIp())) {
                seenIps.add(event.getIp());
            }
            if (notBlank(event.getDevice())) {
                seenDevices.add(event.getDevice());
            }

            if ("KO".equalsIgnoreCase(event.getStatus())) {
                cumulativeKos++;
                currentKoStreak++;
            } else {
                currentKoStreak = 0;
            }
            longestKoStreak = Math.max(longestKoStreak, currentKoStreak);

            while (!downloadWindow.isEmpty() && currentTime != null
                    && Duration.between(downloadWindow.peekFirst(), currentTime).getSeconds() > properties.getDownloadWindowSeconds()) {
                downloadWindow.removeFirst();
            }

            int isDownload = isDownloadAction(event.getAction()) ? 1 : 0;
            if (isDownload == 1 && currentTime != null) {
                downloadCount++;
                downloadWindow.addLast(currentTime);
            }
            int downloadsLast2Minutes = downloadWindow.size();

            if (index >= 2
                    && same(event.getAction(), ordered.get(index - 2).getAction())
                    && !same(event.getAction(), ordered.get(index - 1).getAction())) {
                pingPongCount++;
            }

            event.setPrevAction(prev == null ? "" : safeString(prev.getAction()));
            event.setNextAction(next == null ? "" : safeString(next.getAction()));
            event.setSessionDurationSeconds(totalDurationSeconds);
            event.setTimeDeltaSinceLastAction(timeDelta);
            event.setHourOfDay(currentTime == null ? 0 : currentTime.atZone(ZoneOffset.UTC).getHour());
            event.setDayOfWeek(currentTime == null ? 0 : currentTime.atZone(ZoneOffset.UTC).getDayOfWeek().getValue() - 1);
            event.setIsWeekend((event.getDayOfWeek() != null && event.getDayOfWeek() >= 5) ? 1 : 0);
            event.setIsIpChanged(isChanged(event.getIp(), firstIp));
            event.setUniqueIpsInSession(Math.max(1, seenIps.size()));
            event.setCumulativeKOs(cumulativeKos);
            event.setLongestKoStreak(longestKoStreak);
            event.setHasLoggedIn(hasLoggedIn);
            event.setIsDeviceChanged(isChanged(event.getDevice(), firstDevice));
            event.setUniqueDevicesInSession(Math.max(1, seenDevices.size()));
            event.setIsDownloadAction(isDownload);
            event.setDownloadActionsInSession(downloadCount);
            event.setDownloadsLast2Minutes(downloadsLast2Minutes);
            event.setPingPongCount(pingPongCount);
            event.setSessionRiskScore((double) computeRiskScore(event, timeDelta, hasLoggedIn));

            if (LOGIN_ACTIONS.contains(safeString(event.getAction()))) {
                hasLoggedIn = 1;
            }
        }

        return ordered;
    }

    public SessionSummary buildSessionSummary(List<AuditTrailEvent> sessionEvents) {
        if (sessionEvents == null || sessionEvents.isEmpty()) {
            return SessionSummary.builder()
                    .anomalyTypes(List.of())
                    .campaignIds(List.of())
                    .actionSequence(List.of())
                    .routeSequence(List.of())
                    .actionCounts(Map.of())
                    .primaryAnomalyType("normal")
                    .build();
        }

        List<AuditTrailEvent> ordered = new ArrayList<>(sessionEvents);
        ordered.forEach(this::normalizeEventFields);
        ordered.sort(eventComparator());

        AuditTrailEvent first = ordered.get(0);
        AuditTrailEvent last = ordered.get(ordered.size() - 1);
        Instant start = first.getCreatedAt();
        Instant end = last.getCreatedAt();
        long durationSeconds = start != null && end != null
                ? Math.max(0, Duration.between(start, end).getSeconds())
                : 0;

        List<Long> interActionSeconds = ordered.stream()
                .skip(1)
                .map(AuditTrailEvent::getTimeDeltaSinceLastAction)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<String, Long> actionCounts = new LinkedHashMap<>();
        Set<String> uniqueRoutes = new LinkedHashSet<>();
        Set<String> uniqueIps = new LinkedHashSet<>();
        Set<String> uniqueDevices = new LinkedHashSet<>();
        List<String> actionSequence = new ArrayList<>(ordered.size());
        List<String> routeSequence = new ArrayList<>(ordered.size());
        List<String> anomalyTypes = new ArrayList<>();
        Set<String> campaignIds = new LinkedHashSet<>();
        List<Double> riskScores = new ArrayList<>(ordered.size());
        int totalKos = 0;
        int totalOks = 0;
        int totalDownloads = 0;
        int maxDownloadsIn2Minutes = 0;
        int maxPingPongCount = 0;
        int anomalyEventCount = 0;

        for (AuditTrailEvent event : ordered) {
            String action = safeString(event.getAction());
            actionSequence.add(action);
            actionCounts.put(action, actionCounts.getOrDefault(action, 0L) + 1);

            if (notBlank(event.getRoute())) {
                uniqueRoutes.add(event.getRoute());
                routeSequence.add(event.getRoute());
            }
            if (notBlank(event.getIp())) {
                uniqueIps.add(event.getIp());
            }
            if (notBlank(event.getDevice())) {
                uniqueDevices.add(event.getDevice());
            }
            if ("KO".equalsIgnoreCase(event.getStatus())) {
                totalKos++;
            }
            if ("OK".equalsIgnoreCase(event.getStatus())) {
                totalOks++;
            }
            totalDownloads += defaultInt(event.getIsDownloadAction());
            maxDownloadsIn2Minutes = Math.max(maxDownloadsIn2Minutes, defaultInt(event.getDownloadsLast2Minutes()));
            maxPingPongCount = Math.max(maxPingPongCount, defaultInt(event.getPingPongCount()));

            if (event.getSessionRiskScore() != null) {
                riskScores.add(event.getSessionRiskScore());
            }
            if (defaultInt(event.getIsAnomaly()) == 1) {
                anomalyEventCount++;
            }
            if (notBlank(event.getAnomalyType()) && !"normal".equalsIgnoreCase(event.getAnomalyType())) {
                anomalyTypes.add(event.getAnomalyType());
            }
            if (notBlank(event.getCampaignId())) {
                campaignIds.add(event.getCampaignId());
            }
        }

        Map<String, Long> anomalyTypeCounts = new LinkedHashMap<>();
        for (String anomalyType : anomalyTypes) {
            anomalyTypeCounts.put(anomalyType, anomalyTypeCounts.getOrDefault(anomalyType, 0L) + 1);
        }
        String primaryAnomalyType = anomalyTypeCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("normal");

        ZonedDateTime startTime = start == null ? Instant.EPOCH.atZone(ZoneOffset.UTC) : start.atZone(ZoneOffset.UTC);
        ZonedDateTime endTime = end == null ? Instant.EPOCH.atZone(ZoneOffset.UTC) : end.atZone(ZoneOffset.UTC);

        return SessionSummary.builder()
                .sessionId(first.getSessionId())
                .insuredId(first.getInsuredId())
                .persona(first.getPersona())
                .countryCode(first.getCountryCode())
                .city(first.getCity())
                .month(notBlank(first.getMonth()) ? first.getMonth() : DateTimeFormatter.ofPattern("yyyy-MM").format(startTime))
                .sessionNumber(first.getSessionNumber())
                .sessionStart(start)
                .sessionEnd(end)
                .startHour(startTime.getHour())
                .endHour(endTime.getHour())
                .dayOfWeek(startTime.getDayOfWeek().getValue() - 1)
                .isWeekend((startTime.getDayOfWeek().getValue() - 1) >= 5 ? 1 : 0)
                .firstAction(safeString(first.getAction()))
                .lastAction(safeString(last.getAction()))
                .firstRoute(safeString(first.getRoute()))
                .lastRoute(safeString(last.getRoute()))
                .totalEvents(ordered.size())
                .totalDurationSeconds(durationSeconds)
                .avgInterActionSeconds(average(interActionSeconds))
                .minInterActionSeconds(interActionSeconds.stream().mapToDouble(Long::doubleValue).min().orElse(0.0))
                .maxInterActionSeconds(interActionSeconds.stream().mapToDouble(Long::doubleValue).max().orElse(0.0))
                .uniqueActions(actionCounts.size())
                .uniqueRoutes(uniqueRoutes.size())
                .uniqueIpsUsed(Math.max(1, uniqueIps.size()))
                .uniqueDevicesUsed(Math.max(1, uniqueDevices.size()))
                .totalKOs(totalKos)
                .totalOKs(totalOks)
                .longestKoStreak(ordered.stream()
                        .map(AuditTrailEvent::getLongestKoStreak)
                        .filter(java.util.Objects::nonNull)
                        .max(Integer::compareTo)
                        .orElse(0))
                .hasLogin(hasAnyAction(ordered, LOGIN_ACTIONS))
                .hasLogout(hasAnyAction(ordered, LOGOUT_ACTIONS))
                .ipChanged(ordered.stream().map(AuditTrailEvent::getIsIpChanged).filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0))
                .deviceChanged(ordered.stream().map(AuditTrailEvent::getIsDeviceChanged).filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(0))
                .totalDownloadActions(totalDownloads)
                .maxDownloadsIn2Minutes(maxDownloadsIn2Minutes)
                .pingPongCount(maxPingPongCount)
                .riskScoreMax(riskScores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0))
                .riskScoreAvg(riskScores.isEmpty() ? 0.0 : riskScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0))
                .endedAbruptly(hasAnyAction(ordered, LOGOUT_ACTIONS) == 1 ? 0 : (ordered.size() >= 3 ? 1 : 0))
                .anomalyEventCount(anomalyEventCount)
                .primaryAnomalyType(primaryAnomalyType)
                .anomalyTypes(anomalyTypes.stream().distinct().toList())
                .campaignIds(new ArrayList<>(campaignIds))
                .actionSequence(new ArrayList<>(actionSequence))
                .routeSequence(new ArrayList<>(routeSequence))
                .actionSequenceSignature(String.join(" > ", actionSequence))
                .routeSequenceSignature(String.join(" > ", routeSequence))
                .actionCounts(actionCounts)
                .build();
    }

    private int computeRiskScore(AuditTrailEvent event, long timeDelta, int hasLoggedIn) {
        int score = 0;
        if (defaultInt(event.getIsIpChanged()) == 1) {
            score += 30;
        }
        if ("KO".equalsIgnoreCase(event.getStatus())) {
            score += 10;
        }
        if (defaultInt(event.getIsDeviceChanged()) == 1) {
            score += 25;
        }
        int hourOfDay = defaultInt(event.getHourOfDay());
        if (hourOfDay >= 2 && hourOfDay <= 4) {
            score += 15;
        }
        if (timeDelta > 0 && timeDelta <= properties.getRapidActionSeconds()) {
            score += 15;
        }
        if (defaultInt(event.getCumulativeKOs()) >= 3) {
            score += 15;
        }
        if (defaultInt(event.getDownloadsLast2Minutes()) >= 10) {
            score += 20;
        }
        if (defaultInt(event.getPingPongCount()) >= 2) {
            score += 15;
        }
        if (Set.of("geo_jump", "data_exfiltration", "impossible_device_switch",
                "distributed_brute_force", "zombie_session").contains(safeString(event.getAnomalyType()))) {
            score += 10;
        }
        if ("skip_login".equalsIgnoreCase(safeString(event.getAnomalyType())) && hasLoggedIn == 0) {
            score += 15;
        }
        return Math.min(100, score);
    }

    private boolean isDownloadAction(String action) {
        if (!notBlank(action)) {
            return false;
        }
        String normalized = TextNormalization.comparisonKey(action);
        for (String word : DOWNLOAD_WORDS) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private Comparator<AuditTrailEvent> eventComparator() {
        return Comparator
                .comparing(AuditTrailEvent::getSequenceInSession, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(AuditTrailEvent::getCreatedAt, Comparator.nullsLast(Instant::compareTo))
                .thenComparing(AuditTrailEvent::getId, Comparator.nullsLast(String::compareTo));
    }

    private int isChanged(String current, String first) {
        if (!notBlank(current) || !notBlank(first)) {
            return 0;
        }
        return current.equals(first) ? 0 : 1;
    }

    private int hasAnyAction(List<AuditTrailEvent> events, Set<String> actions) {
        return events.stream()
                .map(AuditTrailEvent::getAction)
                .anyMatch(action -> actions.stream().anyMatch(candidate -> TextNormalization.equalsNormalized(action, candidate)))
                ? 1
                : 0;
    }

    private double average(List<Long> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private float numericValue(Object value, double fallback) {
        if (value == null) {
            return (float) fallback;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return (float) fallback;
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String safeString(String value) {
        return value == null ? "" : TextNormalization.normalizeLabel(value);
    }

    private boolean same(String left, String right) {
        return TextNormalization.equalsNormalized(left, right);
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private void normalizeEventFields(AuditTrailEvent event) {
        if (event == null) {
            return;
        }
        if (!notBlank(event.getAction())) {
            event.setAction(firstNonBlank(event.getActionValue(), event.getFrontendActionName()));
        }
        if (!notBlank(event.getRoute())) {
            event.setRoute(firstNonBlank(event.getPage(), event.getApiTemplate()));
        }
        if (!notBlank(event.getType())) {
            event.setType(event.getActionType());
        }
        if (!notBlank(event.getSubType())) {
            event.setSubType(event.getActionSubtype());
        }
        if (!notBlank(event.getCountryCode())) {
            event.setCountryCode(event.getIpCountry());
        }
        if (event.getSequenceInSession() == null) {
            event.setSequenceInSession(event.getSessionActionSeq());
        }
        event.setAction(TextNormalization.normalizeLabel(event.getAction()));
        event.setPrevAction(TextNormalization.normalizeLabel(event.getPrevAction()));
        event.setNextAction(TextNormalization.normalizeLabel(event.getNextAction()));
        event.setRoute(TextNormalization.normalizeLabel(event.getRoute()));
        event.setPersona(TextNormalization.normalizeLabel(event.getPersona()));
        event.setCountryCode(TextNormalization.normalizeLabel(event.getCountryCode()));
        event.setCity(TextNormalization.normalizeLabel(event.getCity()));
        event.setMonth(TextNormalization.normalizeLabel(event.getMonth()));
        event.setType(TextNormalization.normalizeLabel(event.getType()));
        event.setSubType(TextNormalization.normalizeLabel(event.getSubType()));
        event.setAnomalyType(TextNormalization.normalizeLabel(event.getAnomalyType()));
        event.setCampaignId(TextNormalization.normalizeLabel(event.getCampaignId()));
    }

    private String firstNonBlank(String first, String second) {
        return notBlank(first) ? first : second;
    }
}
