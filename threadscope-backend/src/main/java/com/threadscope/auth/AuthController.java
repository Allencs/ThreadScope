package com.threadscope.auth;

import com.threadscope.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("!test")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserAccountService users;
    private final LoginAttemptService attempts;
    private final SecurityContextRepository contexts;
    private final CookieCsrfTokenRepository csrf;
    private final AuthenticationManager authenticationManager;

    public AuthController(UserAccountService users, PasswordEncoder encoder, LoginAttemptService attempts,
                          SecurityContextRepository contexts, CookieCsrfTokenRepository csrf) {
        this.users = users;
        this.attempts = attempts;
        this.contexts = contexts;
        this.csrf = csrf;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(users);
        provider.setPasswordEncoder(encoder);
        this.authenticationManager = new ProviderManager(provider);
    }

    public record LoginRequest(String username, String password) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record Me(String username, Role role, boolean mustChangePassword) {}

    @PostMapping("/login")
    public Me login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String username = UserAccountService.normalizeUsername(body.username());
        String ip = clientIp(request);
        attempts.checkAllowed(username, ip);
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                username, body.password() == null ? "" : body.password()));
        } catch (AuthenticationException e) {
            attempts.recordFailure(username, ip);
            log.warn("Login failed user={} ip={} reason={}", username, ip, e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }
        attempts.recordSuccess(username, ip);

        // 登录前的会话作废，换一个新的 Session ID 与 CSRF token，防会话固定
        HttpSession previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        request.getSession(true);
        saveContext(auth, request, response);
        csrf.saveToken(csrf.generateToken(request), request, response);

        AuthUser principal = (AuthUser) auth.getPrincipal();
        users.recordLogin(principal.id());
        log.info("Login succeeded user={} ip={}", username, ip);
        return me(principal);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthUser user, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        if (user != null) log.info("Logout user={}", user.getUsername());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Me me(@AuthenticationPrincipal AuthUser user) {
        AppUser fresh = users.current(user.id())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "登录状态已失效，请重新登录"));
        return new Me(fresh.username(), fresh.role(), fresh.mustChangePassword());
    }

    /** 改密码会让该账号的其他会话失效，当前会话更新到新版本继续使用。 */
    @PostMapping("/password")
    public Me changePassword(@AuthenticationPrincipal AuthUser user, @RequestBody ChangePasswordRequest body,
                             HttpServletRequest request, HttpServletResponse response) {
        int version = users.changeOwnPassword(user.id(), body.currentPassword(), body.newPassword());
        AuthUser updated = new AuthUser(user.id(), user.getUsername(), null, user.role(), true, version);
        saveContext(UsernamePasswordAuthenticationToken.authenticated(updated, null, updated.getAuthorities()),
            request, response);
        log.info("Password changed user={}", user.getUsername());
        return me(updated);
    }

    private void saveContext(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    /** 后端只经 nginx 访问（compose 里没有映射端口），X-Real-IP 由 nginx 设置为真实客户端地址。 */
    static String clientIp(HttpServletRequest request) {
        String real = request.getHeader("X-Real-IP");
        return real == null || real.isBlank() ? request.getRemoteAddr() : real.trim();
    }
}
