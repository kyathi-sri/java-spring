package com.securebank.transfer.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.stream.Collectors;

public record Caller(String subject, boolean admin, boolean support) {

    public static Caller from(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return new Caller(auth.getName(), roles.contains("ROLE_ADMIN"), roles.contains("ROLE_SUPPORT"));
    }

    /** A customer acting through an asynchronous channel (Kafka command). */
    public static Caller customer(String customerId) {
        return new Caller(customerId, false, false);
    }

    public boolean staff() {
        return admin || support;
    }
}
