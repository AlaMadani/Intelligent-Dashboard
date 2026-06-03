package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Raw audit-trail payload consumed from Kafka and kept in Redis session buffers.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditTrailEvent {

    // Core identifiers used to group events into user sessions.
    @JsonAlias("record_id")
    private String id;

    @JsonProperty("insuredId")
    @JsonAlias("insured_id")
    private String insuredId;

    private String status;

    @JsonProperty("sessionId")
    @JsonAlias("session_id")
    private String sessionId;

    private String action;

    @JsonAlias("http_code")
    private Integer httpCode;

    private String ip;

    @JsonAlias("user_agent")
    private String userAgent;

    private String page;

    private String browser;

    private String os;

    // Request and response fragments retained for investigation and replay use cases.
    private JsonNode requestData;

    private JsonNode requestReturn;

    @JsonProperty("createdAt")
    @JsonAlias({"created_at", "timestamp"})
    private Instant createdAt;

    @JsonProperty("date")
    private String eventDate;

    private Integer hour;

    @JsonProperty("day_of_week")
    private Integer rawDayOfWeek;

    @JsonProperty("is_business_hours")
    private Integer isBusinessHours;

    @JsonProperty("http_method")
    private String httpMethod;

    @JsonProperty("action_api")
    private String actionApi;

    @JsonProperty("api_template")
    private String apiTemplate;

    @JsonProperty("api_family")
    private String apiFamily;

    private String controller;

    @JsonProperty("frontend_action_name")
    private String frontendActionName;

    @JsonProperty("action_value")
    private String actionValue;

    @JsonProperty("action_type")
    private String actionType;

    @JsonProperty("action_subtype")
    private String actionSubtype;

    // Functional taxonomy and device dimensions consumed by feature engineering.
    private String type;

    @JsonAlias("environment_id")
    private String environmentId;

    private String device;
    private String persona;

    // Optional business identifiers copied through without further transformation.
    private JsonNode companyIdList;
    private JsonNode companyGroupIdList;
    private JsonNode insurerIdList;
    private JsonNode companySectionIdList;
    private JsonNode insurerCodeIdList;
    private JsonNode healthcareNetworkIdList;
    private JsonNode domainIdList;

    private String subType;

    @JsonAlias("country_code")
    private String countryCode;

    @JsonProperty("ip_country")
    private String ipCountry;

    @JsonProperty("ip_region")
    private String ipRegion;

    private String city;

    private String month;

    private Integer sessionNumber;

    // Session-ordering metadata used to rebuild the event sequence.
    @JsonAlias("sequence_in_session")
    private Integer sequenceInSession;

    @JsonProperty("session_action_seq")
    private Integer sessionActionSeq;

    private Integer sessionLength;

    // Navigation context used by simulators and potential downstream analytics.
    private String prevAction;

    private String route;

    private String nextAction;

    private Long sessionDurationSeconds;

    private Long timeDeltaSinceLastAction;

    @JsonProperty("time_since_prev_action_ms")
    private Long timeSincePrevActionMs;

    @JsonProperty("session_duration_so_far_ms")
    private Long sessionDurationSoFarMs;

    @JsonProperty("request_data_size_bytes")
    private Long requestDataSizeBytes;

    @JsonProperty("response_data_size_bytes")
    private Long responseDataSizeBytes;

    private Integer hourOfDay;

    private Integer dayOfWeek;

    @JsonAlias("is_weekend")
    private Integer isWeekend;

    private Integer isIpChanged;

    private Integer uniqueIpsInSession;

    private Integer cumulativeKOs;

    private Integer longestKoStreak;

    private Integer hasLoggedIn;

    private Integer isDeviceChanged;

    private Integer uniqueDevicesInSession;

    private Integer isDownloadAction;

    private Integer downloadActionsInSession;

    private Integer downloadsLast2Minutes;

    private Integer pingPongCount;

    private Double sessionRiskScore;

    @JsonProperty("is_anomaly")
    private Integer isAnomaly;

    @JsonProperty("anomaly_type")
    private String anomalyType;

    private String campaignId;
}
