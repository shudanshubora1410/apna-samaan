package com.sam.apnasamaan.auth.entity;

/**
 * Tracks how an account was originally provisioned (`LOCAL` email+password vs `GOOGLE` OAuth2).
 */
public enum AuthProvider {
    LOCAL,
    GOOGLE
}
