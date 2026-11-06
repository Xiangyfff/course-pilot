-- Agent 长期记忆：存用户偏好，跨会话复用
-- kind: preference（排课偏好）/ fact（用户说明的事实）/ feedback（对回答的反馈）
CREATE TABLE user_memory (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    kind       VARCHAR(24)  NOT NULL,
    mem_key    VARCHAR(64)  NOT NULL,
    mem_value  VARCHAR(500) NOT NULL,
    source     VARCHAR(32)  NOT NULL,
    updated_at DATETIME     NOT NULL
);
CREATE UNIQUE INDEX uk_user_memory_key ON user_memory (user_id, mem_key);
CREATE INDEX idx_user_memory_user ON user_memory (user_id, updated_at);
