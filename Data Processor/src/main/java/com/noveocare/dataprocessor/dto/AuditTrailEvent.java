package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

// Ignore unknown fields to tolerate upstream metadata changes.
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditTrailEvent {

    private String id;

    // Persisted as UTC ISO-8601 in the payload.
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

    // Raw nested JSON used by downstream feature extraction.
    private JsonNode content;
}
