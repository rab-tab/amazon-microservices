
package com.amazon.order.diagnostics;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class KafkaProducerDiagnostics {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProducerDiagnostics(
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public Map<String, Object> getDiagnostics() {

        Map<String, Object> diagnostics = new HashMap<>();

        /*
         * Read the actual configuration used by the ProducerFactory.
         */
        Map<String, Object> producerConfig =
                kafkaTemplate.getProducerFactory()
                        .getConfigurationProperties();

        Map<String, Object> effectiveConfig = new HashMap<>();

        effectiveConfig.put(
                "acks",
                producerConfig.get(ProducerConfig.ACKS_CONFIG)
        );

        effectiveConfig.put(
                "retries",
                producerConfig.get(ProducerConfig.RETRIES_CONFIG)
        );

        effectiveConfig.put(
                "retry.backoff.ms",
                producerConfig.get(ProducerConfig.RETRY_BACKOFF_MS_CONFIG)
        );

        diagnostics.put("producerConfig", effectiveConfig);

        /*
         * Read the actual Kafka producer metrics.
         */
        Map<String, Double> producerMetrics = new HashMap<>();

        kafkaTemplate.metrics().forEach((metricName, metric) -> {

            String name = metricName.name();

            if ("record-retry-total".equals(name)
                    || "record-error-total".equals(name)
                    || "record-send-total".equals(name)) {

                Object value = metric.metricValue();

                if (value instanceof Number) {
                    producerMetrics.put(
                            name,
                            ((Number) value).doubleValue()
                    );
                }
            }
        });

        diagnostics.put("producerMetrics", producerMetrics);

        return diagnostics;
    }
}
