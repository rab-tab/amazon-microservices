package com.amazon.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    @KafkaListener(topics = "user.registered", groupId = "notification-service")
    public void handleUserRegistered(Map<String, Object> event) {
        String email = (String) event.get("email");
        String firstName = (String) event.get("firstName");
        log.info("📧 Sending WELCOME email to: {} ({})", email, firstName);
        // In production: integrate with SendGrid/AWS SES/Twilio
        sendEmail(email,
            "Welcome to Amazon Clone, " + firstName + "!",
            "Your account has been created successfully.");
    }

    @KafkaListener(topics = "notification.events", groupId = "notification-service")
    public void handleNotificationEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        String userId = (String) event.get("userId");
        String orderId = (String) event.get("orderId");

        switch (type) {
            case "PAYMENT_SUCCESS" -> {
                Object amount = event.get("amount");
                log.info("📧 Payment SUCCESS notification for user: {}, order: {}, amount: {}", userId, orderId, amount);
                sendPushNotification(userId, "Payment Confirmed! 🎉",
                        "Your payment of $" + amount + " was successful. Order #" + orderId + " is confirmed.");
            }
            case "PAYMENT_FAILED" -> {
                log.warn("📧 Payment FAILED notification for user: {}, order: {}", userId, orderId);
                sendPushNotification(userId, "Payment Failed ❌",
                        "Your payment for order #" + orderId + " failed. Please try again.");
            }
            default -> log.info("Received notification event of type: {}", type);
        }
    }

    @KafkaListener(topics = "order.events", groupId = "notification-service")
    public void handleOrderEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        String orderId = (String) event.get("orderId");
        String userId = (String) event.get("userId");

        switch (eventType) {
            case "ORDER_CREATED" ->
                log.info("📦 Order CREATED notification for user: {}, order: {}", userId, orderId);
            case "ORDER_CANCELLED" ->
                log.info("❌ Order CANCELLED notification for user: {}, order: {}", userId, orderId);
            case "ORDER_STATUS_UPDATED" -> {
                String status = event.get("status") != null ? event.get("status").toString() : "UNKNOWN";
                log.info("📋 Order status updated to {} for order: {}", status, orderId);
            }
            default -> log.debug("Received order event: {}", eventType);
        }
    }

    private void sendEmail(String to, String subject, String body) {
        // Stub: In production integrate with email provider
        log.info("EMAIL → To: {}, Subject: {}", to, subject);
    }

    private void sendPushNotification(String userId, String title, String body) {
        // Stub: In production integrate with FCM/APNs
        log.info("PUSH → UserId: {}, Title: {}, Body: {}", userId, title, body);
    }

    private void sendSms(String phone, String message) {
        // Stub: In production integrate with Twilio
        log.info("SMS → Phone: {}, Message: {}", phone, message);
    }
}
