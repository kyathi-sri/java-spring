package com.securebank.transfer.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;

/**
 * Maps raw HTTP outcomes onto {@link RemoteResult} / {@link Lookup}. This is the one place that
 * decides what counts as "definitely not applied" versus "unknown".
 */
final class RemoteCalls {

    private static final Logger log = LoggerFactory.getLogger(RemoteCalls.class);

    private RemoteCalls() {
    }

    static <T> RemoteResult<T> call(RestClient.RequestHeadersSpec<?> spec, Class<T> type, ObjectMapper mapper,
                                    String operation) {
        try {
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                byte[] body = response.getBody().readAllBytes();
                if (status >= 200 && status < 300) {
                    return new RemoteResult.Success<>(mapper.readValue(body, type));
                }
                if (isUnknownStatus(status)) {
                    log.warn("{} returned HTTP {}: outcome unknown", operation, status);
                    return new RemoteResult.Unknown<>("HTTP " + status);
                }
                JsonNode error = parse(body, mapper);
                String code = text(error, "error", "HTTP_" + status);
                String message = text(error, "message", "Rejected with HTTP " + status);
                return new RemoteResult.Rejected<>(status, code, message);
            }, true);
        } catch (RestClientException e) {
            log.warn("{} failed before a response was received: {}", operation, e.getMessage());
            return new RemoteResult.Unknown<>(rootMessage(e));
        }
    }

    static <T> Lookup<T> lookup(RestClient.RequestHeadersSpec<?> spec, Class<T> type, ObjectMapper mapper,
                                String operation) {
        try {
            return spec.exchange((request, response) -> {
                int status = response.getStatusCode().value();
                byte[] body = response.getBody().readAllBytes();
                if (status == 200) {
                    return new Lookup.Found<>(mapper.readValue(body, type));
                }
                if (status == 404) {
                    return new Lookup.NotFound<>();
                }
                return new Lookup.Unavailable<>("HTTP " + status);
            }, true);
        } catch (RestClientException e) {
            log.warn("{} status check failed: {}", operation, e.getMessage());
            return new Lookup.Unavailable<>(rootMessage(e));
        }
    }

    /**
     * 5xx: server error, possibly after applying. 408/429: transient. 401/403: our credentials or
     * config are wrong, which says nothing about the business outcome. Retry instead of compensating.
     */
    private static boolean isUnknownStatus(int status) {
        return status >= 500 || status == 401 || status == 403 || status == 408 || status == 429;
    }

    private static JsonNode parse(byte[] body, ObjectMapper mapper) {
        try {
            return body.length == 0 ? null : mapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : fallback;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
    }
}
