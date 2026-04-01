package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/* DTO for session_analysis records. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionAnalysisDto {
    private Long id;
    private String insuredId;
    private String sessionId;
    private Instant startTime;
    private Instant endTime;
    private Integer sessionLength;
    private Long sessionDurationSeconds;
    private Integer uniqueActionCount;
    private Double koRate;
    private Double meanDeltaSeconds;
    private Double actionDiversity;
    private Map<String, Long> actionCounts;
    private Double aeScore;
    @JsonProperty("isAnomaly")
    private Boolean isAnomaly;
    private String anomalyType;
    private Double typeConfidence;
    private List<String> top3NextActions;
    private Boolean ruleTriggered;
    private String ruleType;
    private Instant createdAt;
}
