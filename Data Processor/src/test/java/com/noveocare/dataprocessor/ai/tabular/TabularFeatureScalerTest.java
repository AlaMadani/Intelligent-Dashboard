package com.noveocare.dataprocessor.ai.tabular;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for TabularFeatureScaler: mean/scale transformation and
 * replacement of non-finite raw feature values.
 */
class TabularFeatureScalerTest {

    /* --- Test methods --- */

    @Test
    void appliesMeanScaleAndReplacesNonFiniteValues() {
        TabularFeatureScaler scaler = new TabularFeatureScaler();
        scaler.setFeatureOrder(List.of("a", "b", "c"));
        scaler.setMean(List.of(1.0, 2.0, 0.0));
        scaler.setScale(List.of(2.0, 0.0, 4.0));
        List<String> warnings = new ArrayList<>();

        double[] scaled = scaler.transform(new double[]{3.0, 4.0, Double.NaN}, warnings);

        assertThat(scaled).containsExactly(1.0, 2.0, 0.0);
        assertThat(warnings).contains("tabular_non_finite_raw_replaced");
    }
}
