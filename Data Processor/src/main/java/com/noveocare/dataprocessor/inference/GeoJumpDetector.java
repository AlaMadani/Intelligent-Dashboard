package com.noveocare.dataprocessor.inference;

import com.noveocare.dataprocessor.config.RuleProperties;
import com.noveocare.dataprocessor.dto.AuditTrailEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Detects sessions whose country context changes mid-stream, which can indicate
 * account sharing, credential abuse, or inconsistent source data.
 */
@Component
@RequiredArgsConstructor
public class GeoJumpDetector {

    /* ---- Dependencies ---- */
    private final RuleProperties ruleProperties;

    /* ---- Public API ---- */

    public boolean isGeoJump(List<AuditTrailEvent> sessionEvents) {
        // Disable the rule entirely when configured off or when there is not enough history to compare.
        if (!ruleProperties.getGeoJump().isEnabled() || sessionEvents.size() < 2) {
            return false;
        }

        String firstCountry = null;
        for (AuditTrailEvent event : sessionEvents) {
            // Use the first non-blank country as the baseline, then look for any later mismatch.
            String country = event.getCountryCode();
            if (country != null && !country.isBlank()) {
                if (firstCountry == null) {
                    firstCountry = country;
                } else if (!firstCountry.equalsIgnoreCase(country)) {
                    return true;
                }
            }
        }

        return false;
    }
}
