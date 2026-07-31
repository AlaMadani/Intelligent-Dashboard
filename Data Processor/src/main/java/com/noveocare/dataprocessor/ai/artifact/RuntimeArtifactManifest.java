package com.noveocare.dataprocessor.ai.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Deserialised V3.6.1 deployment manifest describing the AI resource package.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeArtifactManifest {
    @JsonProperty("package_type")
    private String packageType;
    @JsonProperty("created_by")
    private String createdBy;
    @JsonProperty("compatible_only")
    private boolean compatibleOnly;
    @JsonProperty("java_friendly")
    private boolean javaFriendly;
    @JsonProperty("excluded_formats")
    private List<String> excludedFormats = List.of();
    private Object models = Map.of();
    private List<String> configs = List.of();
    private List<String> reports = List.of();
    private List<String> notes = List.of();
}
