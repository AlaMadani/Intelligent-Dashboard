package com.neo.dashboard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neo.dashboard.config.CorsConfig;
import com.neo.dashboard.dto.StatsResponseDto;
import com.neo.dashboard.mapper.AnomalyEventMapper;
import com.neo.dashboard.mapper.SessionAnalysisMapper;
import com.neo.dashboard.service.ActiveAnomalyService;
import com.neo.dashboard.service.ActiveSessionService;
import com.neo.dashboard.service.AnomalyEventService;
import com.neo.dashboard.service.AnomalyExplanationService;
import com.neo.dashboard.service.AnomalyInvestigationService;
import com.neo.dashboard.service.CommandCenterService;
import com.neo.dashboard.service.DashboardReadService;
import com.neo.dashboard.service.LiveStatsStreamService;
import com.neo.dashboard.service.NextActionPredictionService;
import com.neo.dashboard.service.RiskProfileService;
import com.neo.dashboard.service.SessionAnalysisService;
import com.neo.dashboard.service.SessionInsightReadService;
import com.neo.dashboard.service.StatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyticsControllerTest {

    private final SessionAnalysisService sessionAnalysisService = mock(SessionAnalysisService.class);
    private final AnomalyEventService anomalyEventService = mock(AnomalyEventService.class);
    private final RiskProfileService riskProfileService = mock(RiskProfileService.class);
    private final NextActionPredictionService nextActionPredictionService = mock(NextActionPredictionService.class);
    private final StatsService statsService = mock(StatsService.class);
    private final CommandCenterService commandCenterService = mock(CommandCenterService.class);
    private final ActiveSessionService activeSessionService = mock(ActiveSessionService.class);
    private final AnomalyInvestigationService anomalyInvestigationService = mock(AnomalyInvestigationService.class);
    private final ActiveAnomalyService activeAnomalyService = mock(ActiveAnomalyService.class);
    private final AnomalyExplanationService anomalyExplanationService = mock(AnomalyExplanationService.class);
    private final LiveStatsStreamService liveStatsStreamService = mock(LiveStatsStreamService.class);
    private final DashboardReadService dashboardReadService = mock(DashboardReadService.class);
    private final SessionInsightReadService sessionInsightReadService = mock(SessionInsightReadService.class);
    private final SessionAnalysisMapper sessionAnalysisMapper = mock(SessionAnalysisMapper.class);
    private final AnomalyEventMapper anomalyEventMapper = mock(AnomalyEventMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AnalyticsController controller = new AnalyticsController(
                sessionAnalysisService,
                anomalyEventService,
                riskProfileService,
                nextActionPredictionService,
                statsService,
                commandCenterService,
                activeSessionService,
                anomalyInvestigationService,
                activeAnomalyService,
                anomalyExplanationService,
                liveStatsStreamService,
                dashboardReadService,
                sessionInsightReadService,
                sessionAnalysisMapper,
                anomalyEventMapper,
                objectMapper
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .addFilters(new CorsConfig("http://localhost:9008").corsFilter())
                .build();
    }

    @Test
    void liveStatsEndpointIncludesCorsHeaders() throws Exception {
        LocalDate date = LocalDate.of(2026, 4, 28);
        when(statsService.getLiveStats(date))
                .thenReturn(new StatsResponseDto(date, "missing", objectMapper.createObjectNode()));

        mockMvc.perform(get("/api/v1/stats/live")
                        .param("date", "2026-04-28")
                        .header(HttpHeaders.ORIGIN, "http://localhost:9008"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:9008"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void dashboardSnapshotEndpointReturnsStableEmptyPayloadForSupportedViews() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set("items", objectMapper.createArrayNode());
        when(dashboardReadService.getSnapshotOrDefault("cluster-mix")).thenReturn(Optional.of(payload));

        mockMvc.perform(get("/api/v1/dashboard/cluster-mix")
                        .header(HttpHeaders.ORIGIN, "http://localhost:9008"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:9008"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }
}
