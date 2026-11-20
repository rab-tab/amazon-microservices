package com.amazon.payment.service;


import lombok.RequiredArgsConstructor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KafkaListenerControlService {

    private static final String LISTENER_ID =
            "payment-order-events-listener";

    private final KafkaListenerEndpointRegistry registry;

    public void stopListener() {
        MessageListenerContainer container =
                getListenerContainer();

        if (container.isRunning()) {
            container.stop();
        }
    }

    public void startListener() {
        MessageListenerContainer container =
                getListenerContainer();

        if (!container.isRunning()) {
            container.start();
        }
    }

    public boolean isListenerRunning() {
        return getListenerContainer().isRunning();
    }

    private MessageListenerContainer getListenerContainer() {
        MessageListenerContainer container =
                registry.getListenerContainer(LISTENER_ID);

        if (container == null) {
            throw new IllegalStateException(
                    "Kafka listener not found: " + LISTENER_ID
            );
        }

        return container;
    }
}
