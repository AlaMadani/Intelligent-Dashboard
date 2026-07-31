package com.noveocare.dataprocessor.ai.churn;

import com.noveocare.dataprocessor.ai.V34TestArtifacts;
import com.noveocare.dataprocessor.ai.artifact.ChurnFeatureSchema;
import com.noveocare.dataprocessor.ai.artifact.RuntimeArtifactService;
import com.noveocare.dataprocessor.config.AiChurnProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ExtraTreesJsonChurnInferenceService: feature vector construction
 * in schema order and handling of unknown categorical values.
 */
class ExtraTreesJsonChurnInferenceServiceTest {

    /* --- Test methods --- */

    @Test
    void buildsFeatureVectorInExactSchemaOrderAndDefaultsUnknowns() throws Exception {
        RuntimeArtifactService artifacts = V34TestArtifacts.loadedArtifactService();
        ChurnFeatureSchema schema = artifacts.getChurnFeatureSchema();
        ExtraTreesJsonChurnInferenceService service = new ExtraTreesJsonChurnInferenceService(
                artifacts,
                new AiChurnProperties(),
                new ChurnFeatureAssembler());
        Map<String, Object> features = new LinkedHashMap<>();
        for (String feature : schema.getFeatureOrder()) {
            Map<String, Integer> encoder = schema.getCategoricalColumnsLabelEncodedAsNumeric().get(feature);
            if (encoder != null && !encoder.isEmpty()) {
                features.put(feature, encoder.keySet().iterator().next());
            } else {
                features.put(feature, 1.5);
            }
        }
        List<String> warnings = new ArrayList<>();

        double[] vector = service.buildFeatureVector(features, warnings);

        assertThat(vector).hasSize(schema.getFeatureOrder().size());
        assertThat(warnings).isEmpty();

        String categoricalFeature = schema.getCategoricalColumnsLabelEncodedAsNumeric().keySet().stream().findFirst().orElse(null);
        if (categoricalFeature != null) {
            features.put(categoricalFeature, "not_in_schema");
            warnings.clear();
            double[] defaulted = service.buildFeatureVector(features, warnings);
            int index = schema.getFeatureOrder().indexOf(categoricalFeature);
            assertThat(defaulted[index]).isEqualTo((double) schema.getDefaultUnknownCategoryValue());
            assertThat(warnings).contains("churn_unknown_category_" + categoricalFeature);
        }
    }
}
