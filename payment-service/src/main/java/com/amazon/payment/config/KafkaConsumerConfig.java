package com.amazon.payment.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer Configuration with Dead Letter Queue (DLQ) Support
 *
 * Features:
 * - ErrorHandlingDeserializer wraps JSON deserializer for graceful error handling
 * - DeadLetterPublishingRecoverer sends failed messages to DLQ topic
 * - Exponential backoff retry strategy (3 retries: 1s, 2s, 4s)
 * - DLQ topic naming: {original-topic}.DLQ
 * - Error metadata preserved in DLQ headers
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * Consumer Factory with ErrorHandlingDeserializer
     *
     * This configuration wraps the JSON deserializer with ErrorHandlingDeserializer
     * to handle deserialization failures gracefully instead of crashing the consumer.
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();

        // Basic Kafka settings
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // CRITICAL: Use ErrorHandlingDeserializer as the outer deserializer
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);

        // Specify the actual deserializers to delegate to
        config.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        config.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        // JSON deserializer settings
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        // Consumer behavior
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Manual commit for safety

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Kafka Listener Container Factory with DLQ Error Handler
     *
     * Configures:
     * - Manual acknowledgment mode for precise offset control
     * - DefaultErrorHandler with exponential backoff retry
     * - DeadLetterPublishingRecoverer for sending failed messages to DLQ
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // Configure DLQ Error Handler
        factory.setCommonErrorHandler(defaultErrorHandler(kafkaTemplate));

        return factory;
    }

    /**
     * Default Error Handler with DLQ Support
     *
     * Retry Strategy:
     * - Initial interval: 1000ms (1 second)
     * - Multiplier: 2.0 (doubles each time)
     * - Max attempts: 3 retries
     * - Retry sequence: 1s → 2s → 4s → DLQ
     *
     * After all retries are exhausted, the message is sent to DLQ with error metadata.
     */
    private DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {

        // Exponential backoff configuration
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1000L);      // Start with 1 second
        backOff.setMultiplier(2.0);             // Double the interval each retry
        backOff.setMaxElapsedTime(10000L);      // Maximum 10 seconds total

        // Create DeadLetterPublishingRecoverer
        // This sends failed messages to {original-topic}.DLQ
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (consumerRecord, exception) -> {
                    // DLQ topic naming: original-topic + ".DLQ"
                    String dlqTopic = consumerRecord.topic() + ".DLQ";
                    return new TopicPartition(dlqTopic, consumerRecord.partition());
                }
        );

        // Error metadata will be added to DLQ headers automatically:
        // - kafka_dlt-original-topic
        // - kafka_dlt-original-partition
        // - kafka_dlt-original-offset
        // - kafka_dlt-exception-fqcn (fully qualified class name)
        // - kafka_dlt-exception-message
        // - kafka_dlt-exception-stacktrace

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Optional: Add logic to determine if an exception should be retried
        // errorHandler.addNotRetryableExceptions(DeserializationException.class);

        return errorHandler;
    }
}