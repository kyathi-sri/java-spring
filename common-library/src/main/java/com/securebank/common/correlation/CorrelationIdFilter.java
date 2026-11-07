package com.securebank.common.correlation;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.UUID;

/**
 * Propagates a correlation id across the request lifecycle so a single logical
 * operation can be traced through logs within and across services.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>Reads the {@value #HEADER} inbound header; if absent, generates a UUID.</li>
 *   <li>Stores it in the SLF4J {@link MDC} under {@value #MDC_KEY} so every log line
 *       in the request thread includes it (see logback pattern).</li>
 *   <li>Echoes it back on the response so clients and downstream services can reuse it.</li>
 *   <li>Always clears the MDC in a finally block to avoid leaking ids across pooled threads.</li>
 * </ol>
 *
 * <p>Register this in each service via a {@code FilterRegistrationBean} or component scan.
 */
public class CorrelationIdFilter implements Filter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String correlationId = httpRequest.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        try {
            MDC.put(MDC_KEY, correlationId);
            httpResponse.setHeader(HEADER, correlationId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * Convenience accessor for the current thread's correlation id (may be null
     * outside a request scope).
     */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
