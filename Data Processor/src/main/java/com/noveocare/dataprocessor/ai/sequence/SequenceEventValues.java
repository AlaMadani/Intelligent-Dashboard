package com.noveocare.dataprocessor.ai.sequence;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Structured event values after mapping: categorical and continuous column
 * values, schema validity flag, and warnings.
 */
@Value
@Builder(toBuilder = true)
public class SequenceEventValues {
    String insuredId;
    String sessionId;
    String eventId;
    Instant timestamp;
    List<String> categoricalColumns;
    List<String> continuousColumns;
    Map<String, String> categoricalValues;
    Map<String, Double> continuousValuesRaw;
    boolean schemaValid;
    List<String> warnings;
}
