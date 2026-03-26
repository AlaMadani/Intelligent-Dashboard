package com.neo.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/* DTO for audit trail events stored as JSON (Redis/CosmosDB). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditTrailEvent {

    private String id;

    /* Parse ISO-8601 timestamps into Instant. */
    @JsonProperty("created_at")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
    private Instant createdAt;

    private Boolean success;
    private String action;
    private String object;

    @JsonProperty("object_id")
    private String objectId;

    @JsonProperty("user_key")
    private Integer userKey;

    private String details;

    @JsonProperty("ip_address")
    private String ipAddress;

    /* Raw nested JSON content for feature flags like has_content. */
    private JsonNode content;
}
