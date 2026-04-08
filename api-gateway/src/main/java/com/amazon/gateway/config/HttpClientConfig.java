package com.amazon.gateway.config;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * HTTP Client Configuration with ENFORCED timeouts
 *
 * Sets timeouts at the Netty HTTP client level.
 * This is the most reliable way to enforce timeouts in Spring Cloud Gateway.
 */
@Configuration
@Slf4j
public class HttpClientConfig {

    @Bean
    public ReactorClientHttpConnector reactorClientHttpConnector() {

        log.info("╔═══════════════════════════════════════════════════════╗");
        log.info("║  Configuring HTTP Client with ENFORCED Timeouts      ║");
        log.info("╚═══════════════════════════════════════════════════════╝");

        // Connection pool configuration
        ConnectionProvider connectionProvider = ConnectionProvider.builder("gateway")
                .maxConnections(100)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofSeconds(60))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(120))
                .build();

        // Create HTTP client with timeouts
        HttpClient httpClient = HttpClient.create(connectionProvider)
                // Connect timeout - how long to wait for connection to be established
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)

                // Response timeout - CRITICAL! This enforces the 3-second limit
                // If backend doesn't respond within 3 seconds, request is cancelled
                .responseTimeout(Duration.ofSeconds(3));

        log.info("✅ HTTP Client configured:");
        log.info("   - Connect timeout: 1000ms (1 second)");
        log.info("   - Response timeout: 3000ms (3 seconds)");
        log.info("   - Max connections: 100");
        log.info("   - Connection pool: gateway");

        return new ReactorClientHttpConnector(httpClient);
    }
}