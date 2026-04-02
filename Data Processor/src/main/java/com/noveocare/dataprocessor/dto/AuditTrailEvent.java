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
    private String id;

    @JsonProperty("insuredId")
    private String insuredId;

    private String status;

    @JsonProperty("sessionId")
    private String sessionId;

    private String action;

    private Integer httpCode;

    private String ip;

    private String userAgent;

    // Request and response fragments retained for investigation and replay use cases.
    private JsonNode requestData;

    private JsonNode requestReturn;

    @JsonProperty("createdAt")
    @JsonAlias("created_at")
    private Instant createdAt;

    // Functional taxonomy and device dimensions consumed by feature engineering.
    private String type;

    private String environmentId;

    private String device;

    // Optional business identifiers copied through without further transformation.
    private JsonNode companyIdList;
    private JsonNode companyGroupIdList;
    private JsonNode insurerIdList;
    private JsonNode companySectionIdList;
    private JsonNode insurerCodeIdList;
    private JsonNode healthcareNetworkIdList;
    private JsonNode domainIdList;

    private String subType;

    private String countryCode;

    private String city;

    private String month;

    private Integer sessionNumber;

    // Session-ordering metadata used to rebuild the event sequence.
    private Integer sequenceInSession;

    private Integer sessionLength;

    // Navigation context used by simulators and potential downstream analytics.
    private String prevAction;

    private String route;
}
