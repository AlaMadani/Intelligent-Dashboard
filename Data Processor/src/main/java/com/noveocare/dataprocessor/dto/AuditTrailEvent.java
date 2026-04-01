package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditTrailEvent {

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

    private JsonNode requestData;

    private JsonNode requestReturn;

    @JsonProperty("createdAt")
    @JsonAlias("created_at")
    private Instant createdAt;

    private String type;

    private String environmentId;

    private String device;

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

    private Integer sequenceInSession;

    private Integer sessionLength;

    private String prevAction;

    private String route;
}
