package com.securebank.transfer.client;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Issues this service's own short-lived SERVICE-role token for calls to account-service and
 * transaction-service. The customer's token is never forwarded: saga steps also run from the
 * recovery job, where no customer request exists.
 *
 * <p>Local/dev only (shared HMAC secret). In AWS, replace with an OAuth2 client-credentials grant
 * against the identity provider; the rest of the code is unaffected.
 */
public class ServiceTokenProvider {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(1);

    private final byte[] secret;
    private final String subject;
    private volatile CachedToken cached;

    private record CachedToken(String value, Instant expiresAt) {
    }

    public ServiceTokenProvider(String secret, String subject) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalStateException("securebank.security.jwt-secret must be at least 32 bytes");
        }
        this.subject = subject;
    }

    public String token() {
        CachedToken current = cached;
        if (current == null || Instant.now().plus(REFRESH_BEFORE_EXPIRY).isAfter(current.expiresAt())) {
            synchronized (this) {
                current = cached;
                if (current == null || Instant.now().plus(REFRESH_BEFORE_EXPIRY).isAfter(current.expiresAt())) {
                    current = mint();
                    cached = current;
                }
            }
        }
        return current.value();
    }

    private CachedToken mint() {
        Instant now = Instant.now();
        Instant exp = now.plus(TTL);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("securebank-" + subject)
                .claim("roles", List.of("SERVICE"))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return new CachedToken(jwt.serialize(), exp);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign service token", e);
        }
    }
}
