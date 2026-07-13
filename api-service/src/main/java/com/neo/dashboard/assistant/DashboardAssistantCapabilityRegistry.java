package com.neo.dashboard.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DashboardAssistantCapabilityRegistry {

    private static final Set<String> ALLOWED_COMMAND_TYPES = Set.of(
            "NAVIGATE", "HIGHLIGHT_ELEMENT", "CLICK_ELEMENT", "SEARCH_ALERT", "SEARCH_USER", "SEARCH_SESSION",
            "OPEN_PANEL", "SET_FILTER", "REFRESH_VIEW", "NO_ACTION",
            "EXPLAIN_WITH_AI", "TOGGLE_THEME", "CHANGE_PASSWORD", "LIST_CAPABILITIES", "LIST_HIGHLIGHTABLE"
    );

    private DashboardAssistantManifest manifest;

    private final Map<String, DashboardAssistantManifest.ManifestRoute> routeMap = new LinkedHashMap<>();
    private final Map<String, DashboardAssistantManifest.ManifestElement> elementMap = new LinkedHashMap<>();
    private final Map<String, DashboardAssistantManifest.ManifestPanel> panelMap = new LinkedHashMap<>();
    private final Map<String, DashboardAssistantManifest.ManifestTask> taskMap = new LinkedHashMap<>();
    private final Map<String, DashboardAssistantManifest.ManifestFilter> filterMap = new LinkedHashMap<>();
    private final Map<String, DashboardAssistantManifest.ManifestRefreshTarget> refreshTargetMap = new LinkedHashMap<>();
    private final Map<String, DashboardAssistantManifest.ManifestSearchTarget> searchTargetMap = new LinkedHashMap<>();

    // Synonym index for fast matching: lowercased synonym -> element id
    private final Map<String, String> elementSynonymIndex = new LinkedHashMap<>();
    private final Map<String, String> routeSynonymIndex = new LinkedHashMap<>();
    // Alias index for filter targets: lowercased alias -> filter id
    private final Map<String, String> filterAliasIndex = new LinkedHashMap<>();

    public DashboardAssistantCapabilityRegistry() {
        init();
    }

    @PostConstruct
    public void init() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ClassPathResource resource = new ClassPathResource("dashboard-assistant/dashboardAssistantManifest.json");
            try (InputStream is = resource.getInputStream()) {
                manifest = mapper.readValue(is, DashboardAssistantManifest.class);
            }
            indexManifest();
            log.info("Loaded Dashboard Assistant manifest: {} routes, {} elements, {} tasks, {} panels, {} filters, {} refresh targets, {} search targets",
                    routeMap.size(), elementMap.size(), taskMap.size(), panelMap.size(), filterMap.size(), refreshTargetMap.size(), searchTargetMap.size());
        } catch (Exception e) {
            log.warn("Failed to load dashboard assistant manifest from classpath, using empty registry: {}", e.getMessage());
            manifest = DashboardAssistantManifest.builder()
                    .routes(Collections.emptyList())
                    .elements(Collections.emptyList())
                    .panels(Collections.emptyList())
                    .filters(Collections.emptyList())
                    .refreshTargets(Collections.emptyList())
                    .searchTargets(Collections.emptyList())
                    .build();
        }
    }

    private void indexManifest() {
        if (manifest.getRoutes() != null) {
            for (DashboardAssistantManifest.ManifestRoute route : manifest.getRoutes()) {
                routeMap.put(route.getId(), route);
                if (route.getSynonyms() != null) {
                    for (String syn : route.getSynonyms()) {
                        routeSynonymIndex.put(syn.toLowerCase().trim(), route.getId());
                    }
                }
            }
        }
        if (manifest.getElements() != null) {
            for (DashboardAssistantManifest.ManifestElement el : manifest.getElements()) {
                elementMap.put(el.getId(), el);
                if (el.getSynonyms() != null) {
                    for (String syn : el.getSynonyms()) {
                        elementSynonymIndex.put(syn.toLowerCase().trim(), el.getId());
                    }
                }
            }
        }
        if (manifest.getTasks() != null) {
            for (DashboardAssistantManifest.ManifestTask t : manifest.getTasks()) {
                taskMap.put(t.getId(), t);
            }
        }
        if (manifest.getPanels() != null) {
            for (DashboardAssistantManifest.ManifestPanel p : manifest.getPanels()) {
                panelMap.put(p.getId(), p);
            }
        }
        if (manifest.getFilters() != null) {
            for (DashboardAssistantManifest.ManifestFilter f : manifest.getFilters()) {
                filterMap.put(f.getId(), f);
                if (f.getSynonyms() != null) {
                    for (String syn : f.getSynonyms()) {
                        filterAliasIndex.put(syn.toLowerCase().trim(), f.getId());
                    }
                }
                if (f.getAliases() != null) {
                    for (String alias : f.getAliases()) {
                        filterAliasIndex.put(alias.toLowerCase().trim(), f.getId());
                    }
                }
            }
        }
        if (manifest.getRefreshTargets() != null) {
            for (DashboardAssistantManifest.ManifestRefreshTarget rt : manifest.getRefreshTargets()) {
                refreshTargetMap.put(rt.getId(), rt);
            }
        }
        if (manifest.getSearchTargets() != null) {
            for (DashboardAssistantManifest.ManifestSearchTarget st : manifest.getSearchTargets()) {
                searchTargetMap.put(st.getId(), st);
            }
        }
    }

    // ── Validation methods ──

    public boolean isAllowedCommandType(String type) {
        return type != null && ALLOWED_COMMAND_TYPES.contains(type);
    }

    public boolean isAllowedRoute(String routeId) {
        return routeId != null && routeMap.containsKey(routeId);
    }

    public boolean isAllowedRouteName(String routeName) {
        return routeName != null && routeMap.values().stream().anyMatch(r -> routeName.equals(r.getRouteName()));
    }

    public boolean isAllowedElement(String elementId) {
        return elementId != null && elementMap.containsKey(elementId);
    }

    public boolean isAllowedPanel(String panelId) {
        return panelId != null && panelMap.containsKey(panelId);
    }

    public boolean isAllowedFilter(String filterId) {
        return filterId != null && filterMap.containsKey(filterId);
    }

    public boolean isAllowedRefreshTarget(String targetId) {
        return targetId != null && refreshTargetMap.containsKey(targetId);
    }

    // ── Lookup methods ──

    public String getRouteForElement(String elementId) {
        DashboardAssistantManifest.ManifestElement el = elementMap.get(elementId);
        return el != null ? el.getRouteId() : null;
    }

    public DashboardAssistantManifest.ManifestRoute getRoute(String routeId) {
        return routeMap.get(routeId);
    }

    public DashboardAssistantManifest.ManifestElement getElement(String elementId) {
        return elementMap.get(elementId);
    }

    public DashboardAssistantManifest.ManifestPanel getPanel(String panelId) {
        return panelMap.get(panelId);
    }

    public DashboardAssistantManifest.ManifestTask getTask(String taskId) {
        return taskMap.get(taskId);
    }

    public Map<String, DashboardAssistantManifest.ManifestTask> getTaskMap() {
        return taskMap;
    }

    public DashboardAssistantManifest.ManifestFilter getFilter(String filterId) {
        return filterMap.get(filterId);
    }

    public DashboardAssistantManifest.ManifestRefreshTarget getRefreshTarget(String targetId) {
        return refreshTargetMap.get(targetId);
    }

    public DashboardAssistantManifest.ManifestSearchTarget getSearchTarget(String targetId) {
        return searchTargetMap.get(targetId);
    }

    public List<DashboardAssistantManifest.ManifestRoute> getAllRoutes() {
        return new ArrayList<>(routeMap.values());
    }

    public List<DashboardAssistantManifest.ManifestFilter> getAllFilters() {
        return new ArrayList<>(filterMap.values());
    }

    /**
     * Normalize a route name or alias to a canonical route ID.
     * Accepts: route.id, route.routeName, route.synonyms, route aliases.
     */
    public String normalizeRouteName(String input) {
        if (input == null) return null;
        String trimmed = input.trim();

        // Direct match by route ID
        if (routeMap.containsKey(trimmed)) return trimmed;

        // Match by routeName (display name like "AlertsPage", "Security Overview")
        for (DashboardAssistantManifest.ManifestRoute route : routeMap.values()) {
            if (route.getRouteName() != null && trimmed.equalsIgnoreCase(route.getRouteName())) {
                return route.getId();
            }
            // Also try camelCase variants: "AlertsPage" -> "alerts"
            if (route.getRouteName() != null) {
                String camel = route.getRouteName().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
                if (trimmed.equalsIgnoreCase(camel) || trimmed.equalsIgnoreCase(route.getRouteName().toLowerCase().replace(" ", "-"))) {
                    return route.getId();
                }
            }
        }

        // Match by synonym index
        String lower = trimmed.toLowerCase();
        if (routeSynonymIndex.containsKey(lower)) return routeSynonymIndex.get(lower);
        for (Map.Entry<String, String> entry : routeSynonymIndex.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }

        return null;
    }

    /**
     * Normalize a filter target to its canonical filter ID.
     * Accepts: filter.id, filter.synonyms, filter.aliases.
     */
    public String normalizeFilterTarget(String input) {
        if (input == null) return null;
        String trimmed = input.trim();

        // Direct match by filter ID
        if (filterMap.containsKey(trimmed)) return trimmed;

        // Match by alias index
        String lower = trimmed.toLowerCase();
        if (filterAliasIndex.containsKey(lower)) return filterAliasIndex.get(lower);

        // Partial match in alias index
        for (Map.Entry<String, String> entry : filterAliasIndex.entrySet()) {
            if (lower.contains(entry.getKey()) || entry.getKey().contains(lower)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Find an element by matching the message against element synonyms.
     * Returns the first matching element id, or null.
     */
    public String findElementBySynonym(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase().trim();
        for (Map.Entry<String, String> entry : elementSynonymIndex.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Find an element on a specific route by matching the message against element synonyms.
     * Returns the first matching element id whose routeId matches the given route, or null.
     */
    public String findElementBySynonymOnRoute(String message, String routeId) {
        if (message == null || routeId == null) return null;
        String lower = message.toLowerCase().trim();
        for (Map.Entry<String, String> entry : elementSynonymIndex.entrySet()) {
            if (lower.contains(entry.getKey())) {
                String elementId = entry.getValue();
                DashboardAssistantManifest.ManifestElement el = elementMap.get(elementId);
                if (el != null && routeId.equals(el.getRouteId())) {
                    return elementId;
                }
            }
        }
        return null;
    }

    /**
     * Find a route by matching the message against route synonyms.
     */
    public String findRouteBySynonym(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase().trim();
        for (Map.Entry<String, String> entry : routeSynonymIndex.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Find a filter by matching the message against filter synonyms.
     */
    public String findFilterBySynonym(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase().trim();
        for (DashboardAssistantManifest.ManifestFilter filter : filterMap.values()) {
            if (filter.getSynonyms() != null) {
                for (String syn : filter.getSynonyms()) {
                    if (lower.contains(syn.toLowerCase())) {
                        return filter.getId();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Find a refresh target by matching the message against refresh target synonyms.
     */
    public String findRefreshTargetBySynonym(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase().trim();
        String normalized = stripFillerWords(lower);
        for (DashboardAssistantManifest.ManifestRefreshTarget rt : refreshTargetMap.values()) {
            if (rt.getSynonyms() != null) {
                for (String syn : rt.getSynonyms()) {
                    String synLower = syn.toLowerCase();
                    if (lower.contains(synLower) || normalized.contains(stripFillerWords(synLower))) {
                        return rt.getId();
                    }
                }
            }
        }
        return null;
    }

    private static String stripFillerWords(String text) {
        return text.replaceAll("\\b(the|a|an|my|your|please|just)\\b", " ")
                .replaceAll("\\s+", " ").trim();
    }

    // ── Refresh target normalization ──

    /**
     * Normalize a refresh target alias to its canonical refresh target ID.
     * Accepts: refresh.id, known aliases, or a route ID for fallback.
     */
    public String normalizeRefreshTarget(String input) {
        if (input == null) return null;
        String trimmed = input.trim().toLowerCase();

        // Direct match by refresh target ID
        if (refreshTargetMap.containsKey(trimmed)) return trimmed;

        // Common aliases mapping
        java.util.Map<String, String> aliasMap = java.util.Map.ofEntries(
            java.util.Map.entry("overview", "security-overview"),
            java.util.Map.entry("main", "security-overview"),
            java.util.Map.entry("dashboard", "security-overview"),
            java.util.Map.entry("main dashboard", "security-overview"),
            java.util.Map.entry("home", "security-overview"),
            java.util.Map.entry("alerts-page", "alerts"),
            java.util.Map.entry("alert", "alerts"),
            java.util.Map.entry("runtime", "runtime-health"),
            java.util.Map.entry("health", "runtime-health"),
            java.util.Map.entry("runtime health", "runtime-health"),
            java.util.Map.entry("forecast-page", "forecast"),
            java.util.Map.entry("churn-page", "churn"),
            java.util.Map.entry("user360", "user360"),
            java.util.Map.entry("user 360", "user360"),
            java.util.Map.entry("user-360", "user360"),
            java.util.Map.entry("account", "account")
        );

        String aliasMatch = aliasMap.get(trimmed);
        if (aliasMatch != null && refreshTargetMap.containsKey(aliasMatch)) return aliasMatch;

        // Also try partial alias matching (e.g. "overview data" -> "security-overview")
        for (java.util.Map.Entry<String, String> entry : aliasMap.entrySet()) {
            if (trimmed.contains(entry.getKey()) && refreshTargetMap.containsKey(entry.getValue())) {
                return entry.getValue();
            }
        }

        // Finally try synonym matching
        for (DashboardAssistantManifest.ManifestRefreshTarget rt : refreshTargetMap.values()) {
            if (rt.getSynonyms() != null) {
                for (String syn : rt.getSynonyms()) {
                    if (trimmed.equals(syn.toLowerCase()) || trimmed.contains(syn.toLowerCase())) {
                        return rt.getId();
                    }
                }
            }
        }

        return null;
    }

    // ── Safe clickable elements for CLICK_ELEMENT ──

    private static final java.util.Set<String> SAFE_CLICKABLE_ELEMENT_IDS = java.util.Set.of(
            "btn-grafana",
            "btn-kibana",
            "btn-user-avatar",
            "theme-toggle",
            "sidebar-toggle",
            "overview-refresh-button",
            "alerts-refresh-button",
            "runtime-refresh-button",
            "forecast-refresh-button",
            "churn-refresh-button"
    );

    /**
     * Check if an element ID is in the safe clickable allowlist.
     */
    public boolean isSafeClickableElement(String elementId) {
        return elementId != null && SAFE_CLICKABLE_ELEMENT_IDS.contains(elementId);
    }

    // ── Manifest query methods ──

    public List<String> getAllowedRouteIds() {
        return new ArrayList<>(routeMap.keySet());
    }

    public List<String> getAllowedRouteNames() {
        return routeMap.values().stream().map(DashboardAssistantManifest.ManifestRoute::getRouteName).collect(Collectors.toList());
    }

    public List<String> getAllowedElementIds() {
        return new ArrayList<>(elementMap.keySet());
    }

    public List<String> getAllowedPanelIds() {
        return new ArrayList<>(panelMap.keySet());
    }

    public Set<String> getAllowedCommandTypes() {
        return ALLOWED_COMMAND_TYPES;
    }

    public DashboardAssistantManifest getManifest() {
        return manifest;
    }

    public String buildCapabilitiesContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("Allowed command types: ").append(String.join(", ", ALLOWED_COMMAND_TYPES)).append("\n");
        sb.append("Allowed routes: ").append(String.join(", ", getAllowedRouteIds())).append("\n");
        sb.append("Allowed elements: ").append(String.join(", ", getAllowedElementIds())).append("\n");
        sb.append("Allowed panels: ").append(String.join(", ", getAllowedPanelIds())).append("\n");
        return sb.toString();
    }

    public Map<String, Object> buildCapabilitiesManifest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commandTypes", ALLOWED_COMMAND_TYPES);
        result.put("routes", manifest.getRoutes() != null ? manifest.getRoutes() : List.of());
        result.put("elements", manifest.getElements() != null ? manifest.getElements() : List.of());
        result.put("tasks", manifest.getTasks() != null ? manifest.getTasks() : List.of());
        result.put("panels", manifest.getPanels() != null ? manifest.getPanels() : List.of());
        result.put("filters", manifest.getFilters() != null ? manifest.getFilters() : List.of());
        result.put("refreshTargets", manifest.getRefreshTargets() != null ? manifest.getRefreshTargets() : List.of());
        result.put("searchTargets", manifest.getSearchTargets() != null ? manifest.getSearchTargets() : List.of());
        return result;
    }
}
