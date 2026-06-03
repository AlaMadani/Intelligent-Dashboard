package com.noveocare.dataprocessor.ai.tabular;

import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.ai.sequence.EncodedSequenceEvent;
import com.noveocare.dataprocessor.ai.sequence.SequenceWindowState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class TabularAnomalyFeatureService {
    private static final String CONTRACT = "tabular_anomaly_feature_contract.json";
    private static final String SCALER = "tabular_feature_scaler.json";

    private final RuntimeArtifactService artifactService;
    private final TabularFieldCoverageMonitor coverageMonitor;

    private TabularAnomalyFeatureContract contract;
    private TabularFeatureScaler scaler;

    @PostConstruct
    public void init() throws IOException {
        contract = artifactService.readConfig(CONTRACT, TabularAnomalyFeatureContract.class);
        scaler = artifactService.readConfig(SCALER, TabularFeatureScaler.class);
        if (!contract.getFeatureOrder().equals(scaler.getFeatureOrder())) {
            log.warn("Tabular feature contract and scaler feature order differ");
        }
    }

    public TabularAnomalyFeatureVector build(SequenceWindowState previousState, EncodedSequenceEvent currentEncoded) {
        List<String> warnings = new ArrayList<>();
        List<EncodedSequenceEvent> events = new ArrayList<>();
        if (previousState != null && previousState.getEvents() != null) {
            events.addAll(previousState.getEvents());
        }
        if (currentEncoded != null) {
            events.add(currentEncoded);
            warnings.addAll(currentEncoded.getWarnings() == null ? List.of() : currentEncoded.getWarnings());
        }
        events = events.stream()
                .sorted(Comparator.comparing(EncodedSequenceEvent::getTimestamp, Comparator.nullsLast(java.time.Instant::compareTo)))
                .toList();

        List<String> catCols = artifactService.getSequenceMetadata().getCatCols();
        List<String> contCols = artifactService.getSequenceMetadata().getContCols();
        List<Integer> vocabSizes = artifactService.getSequenceMetadata().getVocabSizes();
        int windowSize = Math.max(1, artifactService.getSequenceMetadata().getWindowSize());

        Map<String, Double> rawMap = new LinkedHashMap<>();
        int unknownCategoricals = 0;
        int categoricalValues = 0;
        for (String feature : contract.getFeatureOrder()) {
            double value;
            if (feature.startsWith("cont_mean__")) {
                value = continuousMean(events, contCols.indexOf(feature.substring("cont_mean__".length())));
            } else if (feature.startsWith("cont_last__")) {
                value = continuousLast(events, contCols.indexOf(feature.substring("cont_last__".length())));
            } else if (feature.startsWith("cont_std__")) {
                value = continuousStd(events, contCols.indexOf(feature.substring("cont_std__".length())));
            } else if (feature.startsWith("cat_last_norm__")) {
                int index = catCols.indexOf(feature.substring("cat_last_norm__".length()));
                long id = categoricalLast(events, index);
                int vocabSize = vocabSize(vocabSizes, index);
                categoricalValues++;
                if (id <= 0L) {
                    unknownCategoricals++;
                }
                value = vocabSize == 0 ? 0.0 : (double) id / vocabSize;
            } else if (feature.startsWith("cat_nunique_norm__")) {
                int index = catCols.indexOf(feature.substring("cat_nunique_norm__".length()));
                int vocabSize = vocabSize(vocabSizes, index);
                value = vocabSize == 0 ? 0.0 : (double) categoricalUniqueKnown(events, index) / vocabSize;
            } else if ("window_length_ratio".equals(feature)) {
                value = Math.min(1.0, (double) events.size() / windowSize);
            } else {
                warnings.add("missing_tabular_feature_" + feature);
                value = 0.0;
            }
            if (!Double.isFinite(value)) {
                warnings.add("tabular_non_finite_raw_replaced");
                value = 0.0;
            }
            rawMap.put(feature, value);
        }

        double[] raw = rawMap.values().stream().mapToDouble(Double::doubleValue).toArray();
        if (raw.length != contract.getFeatureOrder().size() || raw.length != contract.getFeatureCount()) {
            warnings.add("tabular_feature_vector_length_mismatch");
        }
        if (!contract.getFeatureOrder().equals(scaler.getFeatureOrder())) {
            warnings.add("tabular_feature_contract_mismatch_scaler_order");
        }
        double[] scaled = scaler.transform(raw, warnings);
        coverageMonitor.record(warnings, contract.getFeatureOrder().size(), scaled.length, unknownCategoricals, categoricalValues);
        return TabularAnomalyFeatureVector.builder()
                .contractArtifact(CONTRACT)
                .featureOrder(contract.getFeatureOrder())
                .rawValues(raw)
                .scaledValues(scaled)
                .rawFeatureMap(rawMap)
                .warnings(List.copyOf(warnings.stream().distinct().toList()))
                .build();
    }

    public TabularAnomalyFeatureContract contract() {
        return contract;
    }

    private double continuousMean(List<EncodedSequenceEvent> events, int index) {
        if (index < 0 || events.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (EncodedSequenceEvent event : events) {
            total += continuousValue(event, index);
        }
        return total / events.size();
    }

    private double continuousLast(List<EncodedSequenceEvent> events, int index) {
        if (index < 0 || events.isEmpty()) {
            return 0.0;
        }
        return continuousValue(events.get(events.size() - 1), index);
    }

    private double continuousStd(List<EncodedSequenceEvent> events, int index) {
        if (index < 0 || events.size() < 2) {
            return 0.0;
        }
        double mean = continuousMean(events, index);
        double sum = 0.0;
        for (EncodedSequenceEvent event : events) {
            double diff = continuousValue(event, index) - mean;
            sum += diff * diff;
        }
        return Math.sqrt(sum / events.size());
    }

    private double continuousValue(EncodedSequenceEvent event, int index) {
        if (event == null || event.getContinuousValues() == null || index < 0 || index >= event.getContinuousValues().length) {
            return 0.0;
        }
        return event.getContinuousValues()[index];
    }

    private long categoricalLast(List<EncodedSequenceEvent> events, int index) {
        if (index < 0 || events.isEmpty()) {
            return 0L;
        }
        EncodedSequenceEvent last = events.get(events.size() - 1);
        return last.getCategoricalIds() == null || index >= last.getCategoricalIds().length ? 0L : last.getCategoricalIds()[index];
    }

    private int categoricalUniqueKnown(List<EncodedSequenceEvent> events, int index) {
        if (index < 0 || events.isEmpty()) {
            return 0;
        }
        Set<Long> values = new LinkedHashSet<>();
        for (EncodedSequenceEvent event : events) {
            if (event.getCategoricalIds() != null && index < event.getCategoricalIds().length && event.getCategoricalIds()[index] > 0L) {
                values.add(event.getCategoricalIds()[index]);
            }
        }
        return values.size();
    }

    private int vocabSize(List<Integer> vocabSizes, int index) {
        return index < 0 || index >= vocabSizes.size() || vocabSizes.get(index) == null ? 0 : vocabSizes.get(index);
    }
}
