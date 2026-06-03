package com.noveocare.dataprocessor.ai.tabular;

import com.noveocare.dataprocessor.config.CacheKeys;
import com.noveocare.dataprocessor.config.RedisCacheProperties;
import com.noveocare.dataprocessor.redis.RedisCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
public class TabularFieldCoverageMonitor {
    private final RedisCacheService redisCacheService;
    private final RedisCacheProperties cacheProperties;

    private final AtomicLong missingFeatureCount = new AtomicLong();
    private final AtomicLong defaultedFeatureCount = new AtomicLong();
    private final AtomicLong unknownCategoricalCount = new AtomicLong();
    private final AtomicLong categoricalValueCount = new AtomicLong();
    private final AtomicLong nonFiniteReplacementCount = new AtomicLong();
    private final AtomicLong featureContractMismatchCount = new AtomicLong();
    private final AtomicLong featureVectorLengthMismatchCount = new AtomicLong();

    public void record(List<String> warnings, int expectedLength, int actualLength, int unknownCategoricals, int categoricalValues) {
        if (warnings != null) {
            for (String warning : warnings) {
                if (warning.startsWith("missing_tabular_feature")) {
                    missingFeatureCount.incrementAndGet();
                }
                if (warning.startsWith("defaulted_tabular_feature")) {
                    defaultedFeatureCount.incrementAndGet();
                }
                if (warning.contains("non_finite")) {
                    nonFiniteReplacementCount.incrementAndGet();
                }
                if (warning.contains("feature_contract_mismatch")) {
                    featureContractMismatchCount.incrementAndGet();
                }
            }
        }
        if (expectedLength != actualLength) {
            featureVectorLengthMismatchCount.incrementAndGet();
        }
        unknownCategoricalCount.addAndGet(Math.max(0, unknownCategoricals));
        categoricalValueCount.addAndGet(Math.max(0, categoricalValues));
        publish();
    }

    public Map<String, Object> snapshot() {
        long categoricalTotal = categoricalValueCount.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missingFeatureCount", missingFeatureCount.get());
        payload.put("defaultedFeatureCount", defaultedFeatureCount.get());
        payload.put("unknownCategoricalRatio", categoricalTotal == 0 ? 0.0 : (double) unknownCategoricalCount.get() / categoricalTotal);
        payload.put("nanInfinityReplacements", nonFiniteReplacementCount.get());
        payload.put("featureContractMismatchCount", featureContractMismatchCount.get());
        payload.put("featureVectorLengthMismatchCount", featureVectorLengthMismatchCount.get());
        payload.put("updatedAt", Instant.now().toString());
        return payload;
    }

    private void publish() {
        redisCacheService.setJson(CacheKeys.tabularFieldCoverageKey(), snapshot(), cacheProperties.getLiveStats());
    }
}
