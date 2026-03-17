package com.noveocare.dataprocessor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

// On ignore tout ce qu'on ne connaît pas (les métadonnées Cosmos DB, eTag, etc.)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditTrailEvent {

    private String id;

    // Utilisation d'Instant pour parser nativement l'ISO 8601 de CosmosDB de manière sécurisée
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

    // NOUVEAU : Indispensable pour notre feature 'has_content' !
    // JsonNode permet de capturer n'importe quel objet JSON imbriqué
    private JsonNode content;
}