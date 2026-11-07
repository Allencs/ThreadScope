package com.threadscope.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.threadscope.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败计数，防暴力破解。三个维度分别计数：
 * 同一 IP 猜同一账号、同一 IP 猜多个账号、多个 IP 猜同一账号。
 * 每次失败都会把计时重置，最后一次失败 lockMinutes 分钟后解锁。
 */
@Component
public class LoginAttemptService {

    private final AuthProperties properties;
    private final Cache<String, Integer> failures;

    public LoginAttemptService(AuthProperties properties) {
        this.properties = properties;
        this.failures = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(properties.lockMinutes()))
            .build();
    }

    public void checkAllowed(String username, String ip) {
        if (count(userIpKey(username, ip)) >= properties.maxFailuresPerUserAndIp()
            || count(ipKey(ip)) >= properties.maxFailuresPerIp()
            || count(userKey(username)) >= properties.maxFailuresPerUser()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LOCKED",
                "登录失败次数过多，请 " + properties.lockMinutes() + " 分钟后再试");
        }
    }

    public void recordFailure(String username, String ip) {
        for (String key : new String[]{userIpKey(username, ip), ipKey(ip), userKey(username)}) {
            failures.asMap().merge(key, 1, Integer::sum);
        }
    }

    public void recordSuccess(String username, String ip) {
        failures.invalidate(userIpKey(username, ip));
    }

    private int count(String key) {
        Integer value = failures.getIfPresent(key);
        return value == null ? 0 : value;
    }

    private static String userIpKey(String username, String ip) {
        return "ui:" + username + "@" + ip;
    }

    private static String ipKey(String ip) {
        return "i:" + ip;
    }

    private static String userKey(String username) {
        return "u:" + username;
    }
}
