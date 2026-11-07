package com.threadscope.auth;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/** 存在 Session 里的登录主体。sessionVersion 与数据库不一致时会话作废。 */
public final class AuthUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final long id;
    private final String username;
    private String passwordHash;
    private final Role role;
    private final boolean enabled;
    private final int sessionVersion;

    public AuthUser(long id, String username, String passwordHash, Role role, boolean enabled, int sessionVersion) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
        this.sessionVersion = sessionVersion;
    }

    public static AuthUser of(AppUser user) {
        return new AuthUser(user.id(), user.username(), user.passwordHash(), user.role(), user.enabled(),
            user.sessionVersion());
    }

    public long id() {
        return id;
    }

    public Role role() {
        return role;
    }

    public int sessionVersion() {
        return sessionVersion;
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
