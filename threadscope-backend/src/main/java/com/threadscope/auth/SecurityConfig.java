package com.threadscope.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 服务端 Session 登录：Session cookie 为 HttpOnly + SameSite=Strict；
 * 另外用双提交 cookie（XSRF-TOKEN / X-XSRF-TOKEN 请求头）防 CSRF，axios 会自动带上请求头。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
        return repository;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper,
                                                   CookieCsrfTokenRepository csrfTokenRepository,
                                                   SecurityContextRepository securityContextRepository,
                                                   ObjectProvider<UserAccountService> users) throws Exception {
        ApiErrorWriter errors = new ApiErrorWriter(mapper);
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .requestCache(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole(Role.ADMIN.name())
                .anyRequest().authenticated())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) ->
                    errors.write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"))
                .accessDeniedHandler((request, response, e) -> {
                    if (e instanceof CsrfException) {
                        errors.write(response, HttpServletResponse.SC_FORBIDDEN, "CSRF_INVALID", "页面已过期，请刷新后重试");
                    } else {
                        errors.write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "没有权限执行此操作");
                    }
                }));
        UserAccountService service = users.getIfAvailable();
        if (service != null) {
            http.addFilterBefore(new SessionValidationFilter(service, errors), AuthorizationFilter.class);
        }
        return http.build();
    }
}
