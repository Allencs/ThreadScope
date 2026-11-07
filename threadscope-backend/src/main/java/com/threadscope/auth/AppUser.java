package com.threadscope.auth;

import java.time.Instant;

public record AppUser(
    long id,
    String username,
    String passwordHash,
    Role role,
    boolean enabled,
    boolean mustChangePassword,
    int sessionVersion,
    Instant createdAt,
    Instant lastLoginAt
) {}
