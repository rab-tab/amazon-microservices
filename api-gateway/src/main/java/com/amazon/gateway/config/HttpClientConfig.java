package com.amazon.gateway.config;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * HTTP Client Configuration with ENFORCED timeouts.
 *
 * FIX: previously hardcoded connect-timeout (1000ms) and response-timeout
 * (3s) as Java literals, completely ignoring the equivalent properties
 * under spring.cloud.gateway.httpclient in application.yml. Since this
 * @Bean replaces Spring Cloud Gateway's own auto-configured
 * ReactorClientHttpConnector (which normally reads those YAML properties
 * automatically), the YAML values were silently dead — editing
 * response-timeout in application.yml had no effect, which caused real
 * confusion when diagnosing timeout behavior under load.
 *
 * Now reads both values from application.yml via @Value, so editing the
 * YAML actually changes behavior as expected, while keeping this bean's
 * explicit connection-pool configuration (which has no direct YAML
 * equivalent in this codebase).
 */
@Configuration
@Slf4j
public class HttpClientConfig {

    @Value("${spring.cloud.gateway.httpclient.connect-timeout:1000}")
    private int connectTimeoutMillis;

    @Value("${spring.cloud.gateway.httpclient.response-timeout:PT3S}")
    private Duration responseTimeout;

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

        // Create HTTP client with timeouts — now sourced from application.yml
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(responseTimeout);

        log.info("✅ HTTP Client configured:");
        log.info("   - Connect timeout: {}ms", connectTimeoutMillis);
        log.info("   - Response timeout: {}", responseTimeout);
        log.info("   - Max connections: 100");
        log.info("   - Connection pool: gateway");

        return new ReactorClientHttpConnector(httpClient);
    }
}