package com.threadscope.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param adminUsername       首次启动（库里还没有用户）时创建的管理员
 * @param adminPassword       为空时生成随机临时密码写进日志，首次登录强制修改
 * @param resetAdminPassword  忘记密码时置为 true 重启：把 adminUsername 重置为 adminPassword 并启用，用完改回 false
 */
@ConfigurationProperties(prefix = "threadscope.auth")
public record AuthProperties(
    @DefaultValue("admin") String adminUsername,
    @DefaultValue("") String adminPassword,
    @DefaultValue("false") boolean resetAdminPassword,
    @DefaultValue("5") int maxFailuresPerUserAndIp,
    @DefaultValue("20") int maxFailuresPerIp,
    @DefaultValue("50") int maxFailuresPerUser,
    @DefaultValue("15") int lockMinutes
) {}
