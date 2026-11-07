CREATE TABLE app_user (
    id                    BIGSERIAL PRIMARY KEY,
    username              TEXT NOT NULL UNIQUE,
    password_hash         TEXT NOT NULL,
    role                  TEXT NOT NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password  BOOLEAN NOT NULL DEFAULT FALSE,
    -- 改密码、改角色、禁用时加一，会话里记的版本对不上就强制重新登录
    session_version       INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at         TIMESTAMPTZ
);

-- 上传者用户名；登录功能上线前的报告为空，只有管理员能删
ALTER TABLE jfr_report ADD COLUMN created_by TEXT;
