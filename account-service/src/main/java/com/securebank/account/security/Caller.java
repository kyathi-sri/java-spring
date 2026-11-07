package com.securebank.account.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Minimal view of the authenticated principal, decoupled from Spring Security types.
 * {@code service} is the machine role used by other SecureBank services (never issued to people).
 */
public record Caller(String subject, boolean admin, boolean support, boolean service) {

    /** Human caller (kept for existing call sites). */
    public Caller(String subject, boolean admin, boolean support) {
        this(subject, admin, support, false);
    }

    public static Caller from(Authentication auth) {
        return new Caller(auth.getName(), hasRole(auth, "ROLE_ADMIN"), hasRole(auth, "ROLE_SUPPORT"),
                hasRole(auth, "ROLE_SERVICE"));
    }

    /** ADMIN, SUPPORT and services may see any account; customers only their own. */
    public boolean canSeeAllAccounts() {
        return admin || support || service;
    }

    private static boolean hasRole(Authentication auth, String role) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (role.equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
