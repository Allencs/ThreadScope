package com.threadscope.auth;

/**
 * ADMIN：管理用户，可删除任何报告；USER：上传与查看全部分析，只能删除自己上传的报告。
 */
public enum Role {
    ADMIN,
    USER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Role parse(String value) {
        if (value == null) throw new IllegalArgumentException("角色不能为空");
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知角色: " + value);
        }
    }
}
