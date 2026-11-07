package com.threadscope.auth;

import com.threadscope.config.AnalysisProperties;
import com.threadscope.controller.AnalysisController;
import com.threadscope.exception.AnalysisNotFoundException;
import com.threadscope.service.AnalysisStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AuthController.class, AdminUserController.class, AnalysisController.class})
@Import({SecurityConfig.class, LoginAttemptService.class})
@EnableConfigurationProperties({AuthProperties.class, AnalysisProperties.class})
class AuthSecurityTest {

    private static final String PASSWORD = "Secret123";
    private static final String PROTECTED = "/api/v1/analysis/x/overview";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PasswordEncoder encoder;

    @MockBean
    private UserAccountService users;

    @MockBean
    private AnalysisStorageService storage;

    /** 模拟数据库：current() 每次都读这里，测试里改它相当于管理员改了账号。 */
    private final Map<Long, AppUser> db = new HashMap<>();

    @BeforeEach
    void setUp() {
        String hash = encoder.encode(PASSWORD);
        db.clear();
        put(new AppUser(1, "alice", hash, Role.USER, true, false, 0, Instant.now(), null));
        put(new AppUser(2, "root", hash, Role.ADMIN, true, false, 0, Instant.now(), null));
        put(new AppUser(3, "fresh", hash, Role.USER, true, true, 0, Instant.now(), null));
        put(new AppUser(4, "gone", hash, Role.USER, false, false, 0, Instant.now(), null));
        put(new AppUser(5, "bob", hash, Role.USER, true, false, 0, Instant.now(), null));

        when(users.loadUserByUsername(anyString())).thenAnswer(inv -> db.values().stream()
            .filter(u -> u.username().equals(inv.getArgument(0)))
            .findFirst()
            .map(AuthUser::of)
            .orElseThrow(() -> new UsernameNotFoundException("unknown")));
        when(users.current(anyLong())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.<Long>getArgument(0))));
        when(users.list()).thenAnswer(inv -> List.copyOf(db.values()));
        when(storage.getOrThrow(anyString())).thenThrow(new AnalysisNotFoundException("x"));
    }

    private void put(AppUser user) {
        db.put(user.id(), user);
    }

    private ResultActions attemptLogin(String username, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf())
            .header("X-Real-IP", ip)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession) attemptLogin(username, PASSWORD, "10.0.0.1")
            .andExpect(status().isOk())
            .andReturn().getRequest().getSession(false);
    }

    @Test
    void apiRequiresLogin() throws Exception {
        mvc.perform(get(PROTECTED))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void loggedInSessionReachesApi() throws Exception {
        MockHttpSession session = login("alice");
        mvc.perform(get(PROTECTED).session(session))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ANALYSIS_NOT_FOUND"));
        mvc.perform(get("/api/v1/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void wrongPasswordAndDisabledAccountGetTheSameAnswer() throws Exception {
        attemptLogin("alice", "Wrong1234", "10.0.0.2")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        attemptLogin("gone", PASSWORD, "10.0.0.2")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        attemptLogin("nobody", PASSWORD, "10.0.0.2")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void repeatedFailuresLockTheAccountForThatIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            attemptLogin("bob", "Wrong1234", "10.9.9.9").andExpect(status().isUnauthorized());
        }
        attemptLogin("bob", PASSWORD, "10.9.9.9")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
        attemptLogin("bob", PASSWORD, "10.8.8.8").andExpect(status().isOk());
    }

    @Test
    void onlyAdminsManageUsers() throws Exception {
        mvc.perform(get("/api/v1/admin/users").session(login("alice")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/users").session(login("root")))
            .andExpect(status().isOk());
    }

    @Test
    void sessionIsRevokedWhenAccountChanges() throws Exception {
        MockHttpSession session = login("alice");
        AppUser alice = db.get(1L);
        put(new AppUser(1, "alice", alice.passwordHash(), alice.role(), true, false, 1, alice.createdAt(), null));
        mvc.perform(get(PROTECTED).session(session))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mvc.perform(get(PROTECTED).session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAccountLosesItsSession() throws Exception {
        MockHttpSession session = login("alice");
        AppUser alice = db.get(1L);
        put(new AppUser(1, "alice", alice.passwordHash(), alice.role(), false, false, 0, alice.createdAt(), null));
        mvc.perform(get(PROTECTED).session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void initialPasswordMustBeChangedFirst() throws Exception {
        MockHttpSession session = login("fresh");
        mvc.perform(get(PROTECTED).session(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/v1/auth/me").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        MockHttpSession session = login("alice");
        mvc.perform(post("/api/v1/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get(PROTECTED).session(session)).andExpect(status().isUnauthorized());
    }
}
