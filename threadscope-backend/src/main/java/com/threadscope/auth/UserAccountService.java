package com.threadscope.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.threadscope.exception.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@Profile("!test")
public class UserAccountService implements UserDetailsService {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");
    private static final String TEMP_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 14;
    /** BCrypt 只取前 72 字节 */
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository repository;
    private final PasswordEncoder encoder;
    /** 每个请求都要核对会话是否仍有效；单实例部署，改动时主动失效，TTL 只是兜底。 */
    private final Cache<Long, Optional<AppUser>> current = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .build();

    public UserAccountService(UserRepository repository, PasswordEncoder encoder) {
        this.repository = repository;
        this.encoder = encoder;
    }

    @Override
    public AuthUser loadUserByUsername(String username) {
        return repository.findByUsername(normalizeUsername(username))
            .map(AuthUser::of)
            .orElseThrow(() -> new UsernameNotFoundException("unknown user"));
    }

    public Optional<AppUser> current(long id) {
        return current.get(id, repository::findById);
    }

    public void recordLogin(long id) {
        repository.recordLogin(id, Instant.now());
        current.invalidate(id);
    }

    public List<AppUser> list() {
        return repository.findAll();
    }

    public Created create(String username, Role role) {
        String name = normalizeUsername(username);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("用户名为 3~32 位小写字母、数字、点、下划线或短横线，且以字母或数字开头");
        }
        if (repository.findByUsername(name).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_EXISTS", "用户名已存在");
        }
        String password = temporaryPassword();
        long id = repository.insert(name, encoder.encode(password), role, true);
        return new Created(require(id), password);
    }

    public AppUser update(AuthUser actor, long id, Role role, Boolean enabled) {
        AppUser target = require(id);
        Role nextRole = role == null ? target.role() : role;
        boolean nextEnabled = enabled == null ? target.enabled() : enabled;
        if (nextRole == target.role() && nextEnabled == target.enabled()) return target;
        if (actor.id() == id) {
            throw new IllegalArgumentException("不能停用自己或取消自己的管理员角色");
        }
        repository.updateRoleAndEnabled(id, nextRole, nextEnabled);
        current.invalidate(id);
        return require(id);
    }

    public String resetPassword(AuthUser actor, long id) {
        if (actor.id() == id) throw new IllegalArgumentException("请在「修改密码」里修改自己的密码");
        require(id);
        String password = temporaryPassword();
        repository.updatePassword(id, encoder.encode(password), true);
        current.invalidate(id);
        return password;
    }

    public void delete(AuthUser actor, long id) {
        if (actor.id() == id) throw new IllegalArgumentException("不能删除自己");
        require(id);
        repository.delete(id);
        current.invalidate(id);
    }

    /** 返回新的会话版本：当前会话更新到这个版本，其他会话随之失效。 */
    public int changeOwnPassword(long id, String currentPassword, String newPassword) {
        AppUser user = require(id);
        if (currentPassword == null || !encoder.matches(currentPassword, user.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD", "当前密码不正确");
        }
        validatePassword(newPassword, user.username());
        if (encoder.matches(newPassword, user.passwordHash())) {
            throw new IllegalArgumentException("新密码不能与当前密码相同");
        }
        repository.updatePassword(id, encoder.encode(newPassword), false);
        current.invalidate(id);
        return require(id).sessionVersion();
    }

    private AppUser require(long id) {
        return repository.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    public static void validatePassword(String password, String username) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("密码至少 8 位");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("密码过长");
        }
        if (!password.chars().anyMatch(Character::isLetter) || !password.chars().anyMatch(Character::isDigit)) {
            throw new IllegalArgumentException("密码需同时包含字母和数字");
        }
        if (!username.isEmpty() && password.toLowerCase(Locale.ROOT).contains(username)) {
            throw new IllegalArgumentException("密码不能包含用户名");
        }
    }

    static String temporaryPassword() {
        while (true) {
            StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
            for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
                sb.append(TEMP_PASSWORD_ALPHABET.charAt(RANDOM.nextInt(TEMP_PASSWORD_ALPHABET.length())));
            }
            String password = sb.toString();
            if (password.chars().anyMatch(Character::isLetter) && password.chars().anyMatch(Character::isDigit)) {
                return password;
            }
        }
    }

    public record Created(AppUser user, String temporaryPassword) {}
}
