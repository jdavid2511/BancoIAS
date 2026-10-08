package com.bancoias.config;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile({"default", "h2"})
public class H2DataSourceConfig {

    @Value("${spring.r2dbc.url}")
    private String configuredUrl;

    @Bean
    public ConnectionFactory connectionFactory() {
        if (configuredUrl.toLowerCase().contains(":mem:")) {
            return ConnectionFactories.get(configuredUrl);
        }
        Path dbPath = Path.of("data", "bancoias").toAbsolutePath().normalize();
        ConnectionFactoryOptions options = ConnectionFactoryOptions.builder()
                .option(ConnectionFactoryOptions.DRIVER, "h2")
                .option(ConnectionFactoryOptions.PROTOCOL, "file")
                .option(ConnectionFactoryOptions.DATABASE, dbPath.toString().replace('\\', '/'))
                .build();
        ConnectionFactory base = ConnectionFactories.get(options);
        ConnectionPoolConfiguration poolConfig = ConnectionPoolConfiguration.builder(base)
                .maxSize(20)
                .maxIdleTime(Duration.ofSeconds(30))
                .build();
        return new ConnectionPool(poolConfig);
    }
}