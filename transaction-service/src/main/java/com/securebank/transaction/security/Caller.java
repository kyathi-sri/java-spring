package com.securebank.transaction.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticated principal. {@code SERVICE} is the machine role used by other SecureBank
 * services (e.g. transfer-service posting entries); it is never issued to people.
 */
public record Caller(String subject, boolean admin, boolean support, boolean service) {

    public static Caller from(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return new Caller(auth.getName(), roles.contains("ROLE_ADMIN"),
                roles.contains("ROLE_SUPPORT"), roles.contains("ROLE_SERVICE"));
    }

    /** Staff and services read any account; customers only lines tagged with their own id. */
    public boolean canSeeAllAccounts() {
        return admin || support || service;
    }
}
