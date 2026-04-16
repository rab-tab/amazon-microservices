package com.amazon.payment.config;

import lombok.extern.slf4j.Slf4j;
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
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer Configuration with DLQ Support
 */
@EnableKafka
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * Consumer Factory - Simple String deserializer
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> config = new HashMap<>();

        // Basic settings
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

        // Simple String deserializers (we'll parse JSON manually in listener)
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Consumer behavior
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);  // Auto-commit enabled

        log.info("✅ Kafka Consumer Factory configured");
        log.info("   Bootstrap servers: {}", bootstrapServers);
        log.info("   Group ID: {}", groupId);

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Kafka Listener Container Factory with DLQ and Error Handling
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);

        // Use BATCH acknowledgment (works with auto-commit)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);

        // Configure error handler with DLQ
        factory.setCommonErrorHandler(defaultErrorHandler(kafkaTemplate));

        log.info("✅ Kafka Listener Container Factory configured");

        return factory;
    }

    /**
     * Default Error Handler with DLQ Support
     */
    private DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {

        // No retries - send directly to DLQ on failure
        FixedBackOff backOff = new FixedBackOff(0L, 0L);

        // Create DLQ recoverer
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (consumerRecord, exception) -> {
                    String dlqTopic = consumerRecord.topic() + ".DLQ";

                    log.error("════════════════════════════════════════════════════════");
                    log.error("❌ SENDING MESSAGE TO DLQ");
                    log.error("   Original Topic: {}", consumerRecord.topic());
                    log.error("   DLQ Topic: {}", dlqTopic);
                    log.error("   Key: {}", consumerRecord.key());
                    log.error("   Value: {}", consumerRecord.value());
                    log.error("   Exception: {}", exception.getMessage());
                    log.error("════════════════════════════════════════════════════════");

                    return new TopicPartition(dlqTopic, 0);
                }
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        return errorHandler;
    }
}