package com.threadscope.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * 每个请求核对会话对应的账号：被删除、停用、改过密码或角色的旧会话立即作废；
 * 需要改密码的账号只能访问改密码相关接口。顺便把用户名放进日志 MDC。
 */
final class SessionValidationFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_BEFORE_PASSWORD_CHANGE = Set.of(
        "/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/logout");

    private final UserAccountService users;
    private final ApiErrorWriter errors;

    SessionValidationFilter(UserAccountService users, ApiErrorWriter errors) {
        this.users = users;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser principal)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AppUser> fresh = users.current(principal.id());
        if (fresh.isEmpty() || !fresh.get().enabled() || fresh.get().sessionVersion() != principal.sessionVersion()) {
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            errors.write(response, HttpServletResponse.SC_UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录");
            return;
        }
        if (fresh.get().mustChangePassword() && !ALLOWED_BEFORE_PASSWORD_CHANGE.contains(request.getRequestURI())) {
            errors.write(response, HttpServletResponse.SC_FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码");
            return;
        }
        MDC.put("user", principal.getUsername());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("user");
        }
    }
}
