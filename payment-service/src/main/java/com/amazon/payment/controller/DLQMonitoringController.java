package com.amazon.payment.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.StreamSupport;

/**
 * DLQ Monitoring Controller
 *
 * Provides endpoints to inspect and manage Dead Letter Queue events.
 *
 * Endpoints:
 * - GET /actuator/dlq/{topic} - List failed events in DLQ
 * - GET /actuator/dlq/{topic}/count - Count of events in DLQ
 * - POST /actuator/dlq/{topic}/reprocess - Reprocess events from DLQ
 */
@RestController
@RequestMapping("/actuator/dlq")
@RequiredArgsConstructor
@Slf4j
public class DLQMonitoringController {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Get failed events from DLQ topic
     *
     * @param topic Original topic name (e.g., "payment.request")
     * @param limit Max number of events to retrieve (default: 10)
     * @return List of failed events with error metadata
     */
    @GetMapping("/{topic}")
    public ResponseEntity<?> getDLQEvents(
            @PathVariable String topic,
            @RequestParam(defaultValue = "10") int limit) {

        String dlqTopic = topic + ".DLQ";
        log.info("Fetching DLQ events from topic: {}", dlqTopic);

        try {
            List<Map<String, Object>> events = new ArrayList<>();

            // Create temporary consumer to read DLQ
            Consumer<String, String> consumer = createDLQConsumer(dlqTopic);

            try {
                // Poll for events
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));

                for (ConsumerRecord<String, String> record : records) {
                    if (events.size() >= limit) {
                        break;
                    }

                    Map<String, Object> eventData = new HashMap<>();
                    eventData.put("topic", record.topic());
                    eventData.put("partition", record.partition());
                    eventData.put("offset", record.offset());
                    eventData.put("key", record.key());
                    eventData.put("value", record.value());
                    eventData.put("timestamp", Instant.ofEpochMilli(record.timestamp()));

                    // Extract error headers
                    Map<String, String> headers = new HashMap<>();
                    record.headers().forEach(header -> {
                        headers.put(header.key(), new String(header.value()));
                    });
                    eventData.put("headers", headers);

                    // Extract specific error info from headers
                    if (headers.containsKey("kafka_dlt-exception-fqcn")) {
                        eventData.put("exceptionType", headers.get("kafka_dlt-exception-fqcn"));
                    }
                    if (headers.containsKey("kafka_dlt-exception-message")) {
                        eventData.put("exceptionMessage", headers.get("kafka_dlt-exception-message"));
                    }
                    if (headers.containsKey("kafka_dlt-original-topic")) {
                        eventData.put("originalTopic", headers.get("kafka_dlt-original-topic"));
                    }

                    events.add(eventData);
                }

            } finally {
                consumer.close();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("dlqTopic", dlqTopic);
            response.put("count", events.size());
            response.put("events", events);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching DLQ events", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch DLQ events",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get count of events in DLQ
     *
     * @param topic Original topic name
     * @return Count of events in DLQ
     */
    @GetMapping("/{topic}/count")
    public ResponseEntity<?> getDLQCount(@PathVariable String topic) {
        String dlqTopic = topic + ".DLQ";

        try {
            Consumer<String, String> consumer = createDLQConsumer(dlqTopic);

            try {
                // Get partition info
                Set<TopicPartition> partitions = consumer.assignment();

                long totalCount = 0;
                for (TopicPartition partition : partitions) {
                    long endOffset = consumer.endOffsets(Collections.singleton(partition)).get(partition);
                    long currentOffset = consumer.position(partition);
                    totalCount += (endOffset - currentOffset);
                }

                return ResponseEntity.ok(Map.of(
                        "dlqTopic", dlqTopic,
                        "eventCount", totalCount
                ));

            } finally {
                consumer.close();
            }

        } catch (Exception e) {
            log.error("Error counting DLQ events", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to count DLQ events",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Create a temporary consumer for reading DLQ
     */
    private Consumer<String, String> createDLQConsumer(String dlqTopic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-monitor-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ConsumerFactory<String, String> factory = new DefaultKafkaConsumerFactory<>(props);
        Consumer<String, String> consumer = factory.createConsumer();

        // Subscribe to DLQ topic
        consumer.subscribe(Collections.singletonList(dlqTopic));

        return consumer;
    }
}