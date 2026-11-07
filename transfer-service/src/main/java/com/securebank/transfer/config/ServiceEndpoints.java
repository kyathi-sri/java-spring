package com.securebank.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Downstream service locations and HTTP timeouts (a timeout means "outcome unknown", never "failed"). */
@ConfigurationProperties("securebank.services")
public record ServiceEndpoints(
        @DefaultValue("http://localhost:8081") String accountBaseUrl,
        @DefaultValue("http://localhost:8082") String transactionBaseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout
) {
}
