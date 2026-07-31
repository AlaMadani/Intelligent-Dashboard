package com.neo.dashboard.dto.v36;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing the persona/cluster assignment for a user.
 * <p>
 * Indicates whether persona detection is {@link #enabled}, which
 * {@link #cluster} the user belongs to, the human-readable
 * {@link #label}, the {@link #source} of the assignment, and the
 * {@link #confidence} of the classification.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class V36PersonaDisabledDto {
    /** Whether persona detection/clustering is enabled for this user. */
    private Boolean enabled;
    /** Cluster identifier assigned to the user. */
    private Integer cluster;
    /** Human-readable persona label (e.g. "power_user", "new_user"). */
    private String label;
    /** Source system or component that produced this persona assignment. */
    private String source;
    /** Confidence score for the persona assignment (0.0 – 1.0). */
    private Double confidence;
}
