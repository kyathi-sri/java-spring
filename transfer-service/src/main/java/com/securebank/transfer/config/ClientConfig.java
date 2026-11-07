package com.securebank.transfer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.common.correlation.CorrelationIdFilter;
import com.securebank.transfer.client.AccountClient;
import com.securebank.transfer.client.HttpAccountClient;
import com.securebank.transfer.client.HttpLedgerClient;
import com.securebank.transfer.client.LedgerClient;
import com.securebank.transfer.client.ServiceTokenProvider;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ClientConfig {

    @Bean
    ServiceTokenProvider serviceTokenProvider(@Value("${securebank.security.jwt-secret}") String secret) {
        return new ServiceTokenProvider(secret, "transfer-service");
    }

    @Bean
    AccountClient accountClient(RestClient.Builder builder, ServiceEndpoints endpoints, ServiceTokenProvider tokens,
                                ObjectMapper mapper) {
        return new HttpAccountClient(restClient(builder, endpoints.accountBaseUrl(), endpoints.connectTimeout(),
                endpoints.readTimeout(), tokens), mapper);
    }

    @Bean
    LedgerClient ledgerClient(RestClient.Builder builder, ServiceEndpoints endpoints, ServiceTokenProvider tokens,
                              ObjectMapper mapper) {
        return new HttpLedgerClient(restClient(builder, endpoints.transactionBaseUrl(), endpoints.connectTimeout(),
                endpoints.readTimeout(), tokens), mapper);
    }

    /**
     * Strict timeouts (a slow dependency must not pin saga workers), a fresh SERVICE token on every
     * request, and correlation-id propagation so one transfer can be traced across all services.
     */
    public static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration connectTimeout,
                                        Duration readTimeout, ServiceTokenProvider tokens) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.token());
                    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
