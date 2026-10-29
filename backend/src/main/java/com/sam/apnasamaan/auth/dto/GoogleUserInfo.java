package com.sam.apnasamaan.auth.dto;

/**
 * Verified OpenID Connect profile claims returned by Google's userinfo endpoint.
 */
public record GoogleUserInfo(
        String sub,
        String email,
        boolean emailVerified,
        String name
) {
}
