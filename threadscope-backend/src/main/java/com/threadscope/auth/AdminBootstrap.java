package com.threadscope.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 库里没有用户时创建管理员；resetAdminPassword=true 时把管理员密码重置为配置值。 */
@Component
@Profile("!test")
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthProperties properties;
    private final UserRepository repository;
    private final PasswordEncoder encoder;

    public AdminBootstrap(AuthProperties properties, UserRepository repository, PasswordEncoder encoder) {
        this.properties = properties;
        this.repository = repository;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = UserAccountService.normalizeUsername(properties.adminUsername());
        String configured = properties.adminPassword();
        boolean hasConfigured = configured != null && !configured.isBlank();

        if (properties.resetAdminPassword()) {
            if (!hasConfigured) {
                throw new IllegalStateException("THREADSCOPE_AUTH_RESET_ADMIN_PASSWORD=true 时必须设置 THREADSCOPE_AUTH_ADMIN_PASSWORD");
            }
            UserAccountService.validatePassword(configured, username);
            var existing = repository.findByUsername(username);
            if (existing.isPresent()) {
                repository.updatePassword(existing.get().id(), encoder.encode(configured), false);
                repository.updateRoleAndEnabled(existing.get().id(), Role.ADMIN, true);
            } else {
                repository.insert(username, encoder.encode(configured), Role.ADMIN, false);
            }
            log.warn("Admin account '{}' was reset from configuration; set THREADSCOPE_AUTH_RESET_ADMIN_PASSWORD=false again", username);
            return;
        }

        if (repository.count() > 0) return;
        if (hasConfigured) {
            UserAccountService.validatePassword(configured, username);
            repository.insert(username, encoder.encode(configured), Role.ADMIN, false);
            log.info("Created initial admin account '{}' from configuration", username);
        } else {
            String password = UserAccountService.temporaryPassword();
            repository.insert(username, encoder.encode(password), Role.ADMIN, true);
            log.warn("Created initial admin account '{}' with temporary password: {} (must be changed on first login)",
                username, password);
        }
    }
}
