package com.noveocare.dataprocessor.kafka;

import com.noveocare.dataprocessor.config.KafkaConsumerProperties;
import com.noveocare.dataprocessor.config.KafkaTopicProperties;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AuditTrailConsumer partition assignment and rebalance behavior:
 * aggregation across consumers, revocation, diagnostics, and derived fields.
 */
class AuditTrailConsumerPartitionTest {

    /* --- Fields --- */

    private final KafkaTopicProperties topicProperties = new KafkaTopicProperties();
    private final KafkaConsumerProperties consumerProperties = new KafkaConsumerProperties();

    /* --- Setup --- */

    @BeforeEach
    void setUp() {
        topicProperties.setAuditTrail("topic-audit-trail");
        consumerProperties.setConcurrency(5);
    }

    /* --- Test methods: assignment aggregation --- */

    @Test
    void aggregatesAssignmentsAcrossMultipleConsumers() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);
        consumer.setTopicPartitionCount(6);

        consumer.setTestConsumerIdOverride("consumer-1");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(0, 1));
        consumer.setTestConsumerIdOverride("consumer-2");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(2, 3));
        consumer.setTestConsumerIdOverride("consumer-3");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(4, 5));

        Map<String, Object> diag = consumer.diagnosticsSnapshot();
        @SuppressWarnings("unchecked")
        List<String> assigned = (List<String>) diag.get("assignedPartitions");

        assertThat(assigned).containsExactlyInAnyOrder(
                "topic-audit-trail-0", "topic-audit-trail-1",
                "topic-audit-trail-2", "topic-audit-trail-3",
                "topic-audit-trail-4", "topic-audit-trail-5");
        assertThat(diag).containsEntry("assignedPartitionCount", 6);
        assertThat(consumer.getAllAssignedPartitions()).hasSize(6);
    }

    /* --- Test methods: rebalancing --- */

    @Test
    void updatesAssignmentWhenConsumerReassigned() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);
        consumer.setTopicPartitionCount(4);

        consumer.setTestConsumerIdOverride("consumer-1");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(0, 1));
        consumer.setTestConsumerIdOverride("consumer-2");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(2));

        assertThat(consumer.getAllAssignedPartitions()).containsExactlyInAnyOrder(
                "topic-audit-trail-0", "topic-audit-trail-1", "topic-audit-trail-2");

        consumer.setTestConsumerIdOverride("consumer-1");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(0, 1, 3));

        assertThat(consumer.getAllAssignedPartitions()).containsExactlyInAnyOrder(
                "topic-audit-trail-0", "topic-audit-trail-1",
                "topic-audit-trail-2", "topic-audit-trail-3");
    }

    /* --- Test methods: revocation --- */

    @Test
    void removesAssignmentOnRevocation() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);
        consumer.setTopicPartitionCount(4);

        consumer.setTestConsumerIdOverride("consumer-1");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(0, 1));
        consumer.setTestConsumerIdOverride("consumer-2");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(2, 3));

        assertThat(consumer.getAllAssignedPartitions()).hasSize(4);

        consumer.setTestConsumerIdOverride("consumer-2");
        consumer.createRebalanceListener().onPartitionsRevoked(partitions(2, 3));

        assertThat(consumer.getAllAssignedPartitions()).containsExactly("topic-audit-trail-0", "topic-audit-trail-1");
    }

    /* --- Test methods: diagnostics --- */

    @Test
    void reportsTopicPartitionCount() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);
        consumer.setTopicPartitionCount(6);

        Map<String, Object> diag = consumer.diagnosticsSnapshot();
        assertThat(diag).containsEntry("topicPartitionCount", 6);
    }

    /* --- Test methods: derived fields --- */

    @Test
    void derivedFieldsOkWhenFullAssignment() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);
        consumer.setTopicPartitionCount(6);

        consumer.setTestConsumerIdOverride("consumer-1");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(0, 1));
        consumer.setTestConsumerIdOverride("consumer-2");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(2, 3));
        consumer.setTestConsumerIdOverride("consumer-3");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(4, 5));

        Map<String, Object> diag = consumer.diagnosticsSnapshot();
        assertThat(diag).containsEntry("assignedPartitionCount", 6);
        assertThat(diag).containsEntry("effectiveConsumerParallelism", 5);
        assertThat(diag).containsEntry("partitionAssignmentStatus", "OK");
        assertThat((String) diag.get("partitionAssignmentMessage")).contains("All 6 topic partitions are assigned");
    }

    @Test
    void derivedFieldsPartialWhenNotFullyAssigned() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);
        consumer.setTopicPartitionCount(6);

        consumer.setTestConsumerIdOverride("consumer-1");
        consumer.createRebalanceListener().onPartitionsAssigned(partitions(0));

        Map<String, Object> diag = consumer.diagnosticsSnapshot();
        assertThat(diag).containsEntry("assignedPartitionCount", 1);
        assertThat(diag).containsEntry("partitionAssignmentStatus", "PARTIAL");
        assertThat((String) diag.get("partitionAssignmentMessage")).contains("1 of 6");
    }

    @Test
    void configValuesExposedInDiagnostics() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);
        consumer.setAutoOffsetReset("latest");
        consumer.setMaxPollRecords(50);
        consumer.setMaxPollIntervalMs(900000L);

        Map<String, Object> diag = consumer.diagnosticsSnapshot();
        assertThat(diag).containsEntry("autoOffsetReset", "latest");
        assertThat(diag).containsEntry("maxPollRecords", 50);
        assertThat(diag).containsEntry("maxPollIntervalMs", 900000L);
    }

    /* --- Test methods: edge cases --- */

    @Test
    void assignedPartitionsIsNullWhenNoAssignments() {
        AuditTrailConsumer consumer = new AuditTrailConsumer(null, null, null, null, null, null, null, null, null, null,
                topicProperties, consumerProperties, null, null, null, null, null, null, null);

        Map<String, Object> diag = consumer.diagnosticsSnapshot();
        assertThat(diag.get("assignedPartitions")).isNull();
        assertThat(diag).containsEntry("assignedPartitionCount", 0);
    }

    /* --- Helper methods --- */

    private static Collection<TopicPartition> partitions(int... ids) {
        return java.util.Arrays.stream(ids)
                .mapToObj(id -> new TopicPartition("topic-audit-trail", id))
                .toList();
    }
}
