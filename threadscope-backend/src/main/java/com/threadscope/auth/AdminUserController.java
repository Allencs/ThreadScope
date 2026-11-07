package com.threadscope.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户管理，SecurityConfig 限定 ADMIN 角色访问。 */
@RestController
@RequestMapping("/api/v1/admin/users")
@Profile("!test")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final UserAccountService users;

    public AdminUserController(UserAccountService users) {
        this.users = users;
    }

    public record UserView(long id, String username, Role role, boolean enabled, boolean mustChangePassword,
                           long createdAtEpochMs, Long lastLoginAtEpochMs) {
        static UserView of(AppUser user) {
            return new UserView(user.id(), user.username(), user.role(), user.enabled(), user.mustChangePassword(),
                user.createdAt().toEpochMilli(),
                user.lastLoginAt() == null ? null : user.lastLoginAt().toEpochMilli());
        }
    }

    public record CreateUserRequest(String username, String role) {}

    public record UpdateUserRequest(String role, Boolean enabled) {}

    /** 临时密码只在这里返回一次，用户首次登录后必须修改。 */
    public record PasswordIssued(UserView user, String temporaryPassword) {}

    @GetMapping
    public List<UserView> list() {
        return users.list().stream().map(UserView::of).toList();
    }

    @PostMapping
    public PasswordIssued create(@AuthenticationPrincipal AuthUser actor, @RequestBody CreateUserRequest body) {
        UserAccountService.Created created = users.create(body.username(), Role.parse(body.role()));
        log.info("Admin {} created user {} role={}", actor.getUsername(), created.user().username(), created.user().role());
        return new PasswordIssued(UserView.of(created.user()), created.temporaryPassword());
    }

    @PatchMapping("/{id}")
    public UserView update(@AuthenticationPrincipal AuthUser actor, @PathVariable long id,
                           @RequestBody UpdateUserRequest body) {
        AppUser updated = users.update(actor, id, body.role() == null ? null : Role.parse(body.role()), body.enabled());
        log.info("Admin {} updated user {} role={} enabled={}", actor.getUsername(), updated.username(),
            updated.role(), updated.enabled());
        return UserView.of(updated);
    }

    @PostMapping("/{id}/reset-password")
    public PasswordIssued resetPassword(@AuthenticationPrincipal AuthUser actor, @PathVariable long id) {
        String password = users.resetPassword(actor, id);
        AppUser user = users.current(id).orElseThrow();
        log.info("Admin {} reset password of user {}", actor.getUsername(), user.username());
        return new PasswordIssued(UserView.of(user), password);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser actor, @PathVariable long id) {
        users.delete(actor, id);
        log.info("Admin {} deleted user id={}", actor.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
