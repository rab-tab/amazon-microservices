package com.amazon.order.config;

// config/sharding/ShardMigrationConfig.java


import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

//import javax.annotation.PostConstruct;
import java.util.List;

@Configuration
@Profile("sharded")
public class ShardMigrationConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.sharding-migration")
    public ShardMigrationProperties shardMigrationProperties() {
        return new ShardMigrationProperties();
    }

    private final ShardMigrationProperties properties;

    public ShardMigrationConfig(ShardMigrationProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void migrateAllShards() {
        for (ShardMigrationProperties.ShardEndpoint shard : properties.getShards()) {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(shard.getUrl());
            hikariConfig.setUsername(shard.getUsername());
            hikariConfig.setPassword(shard.getPassword());
            hikariConfig.setMaximumPoolSize(2); // migration-only pool, kept small

            try (HikariDataSource migrationDataSource = new HikariDataSource(hikariConfig)) {
                Flyway.configure()
                        .dataSource(migrationDataSource)
                        .locations("classpath:db/migration") // same migration scripts, every shard
                        .load()
                        .migrate();
            }
        }
    }
}