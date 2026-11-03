-- 课程规划 Agent：初始表结构
-- 约定：所有时间列由应用写入（不用库函数默认值），方便测试与时钟统一。

CREATE TABLE app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    DATETIME     NOT NULL
);
CREATE UNIQUE INDEX uk_app_user_username ON app_user (username);

CREATE TABLE program (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    school        VARCHAR(128),
    major         VARCHAR(128),
    grade_year    INT,
    title         VARCHAR(255),
    source_file   VARCHAR(255),
    source_hash   VARCHAR(64),
    source_text   TEXT,
    status        VARCHAR(24)  NOT NULL,
    total_credits DECIMAL(6,1) NOT NULL,
    warnings      TEXT,
    version       INT          NOT NULL,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL
);
CREATE INDEX idx_program_user ON program (user_id, status, created_at);

CREATE TABLE course (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_id    BIGINT       NOT NULL,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    credits       DECIMAL(5,1) NOT NULL,
    module_code   VARCHAR(32)  NOT NULL,
    course_type   VARCHAR(16)  NOT NULL,
    offered_terms VARCHAR(64)  NOT NULL,
    created_at    DATETIME     NOT NULL
);
CREATE INDEX idx_course_program ON course (program_id);
CREATE INDEX idx_course_module ON course (program_id, module_code);
CREATE UNIQUE INDEX uk_course_program_code ON course (program_id, code);

CREATE TABLE course_prereq (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_id  BIGINT      NOT NULL,
    course_code VARCHAR(32) NOT NULL,
    prereq_code VARCHAR(32) NOT NULL,
    created_at  DATETIME    NOT NULL
);
CREATE INDEX idx_prereq_program ON course_prereq (program_id, course_code);

CREATE TABLE module_requirement (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    program_id  BIGINT       NOT NULL,
    module_code VARCHAR(32)  NOT NULL,
    module_name VARCHAR(64)  NOT NULL,
    min_credits DECIMAL(5,1) NOT NULL,
    created_at  DATETIME     NOT NULL
);
CREATE INDEX idx_module_program ON module_requirement (program_id);

CREATE TABLE transcript (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    file_name  VARCHAR(255),
    status     VARCHAR(24)  NOT NULL,
    created_at DATETIME     NOT NULL
);
CREATE INDEX idx_transcript_user ON transcript (user_id, created_at);

CREATE TABLE transcript_course (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    transcript_id BIGINT       NOT NULL,
    code          VARCHAR(32),
    name          VARCHAR(128) NOT NULL,
    credits       DECIMAL(5,1) NOT NULL,
    score         DECIMAL(5,1),
    term          VARCHAR(24),
    passed        BOOLEAN      NOT NULL
);
CREATE INDEX idx_tc_transcript ON transcript_course (transcript_id);

CREATE TABLE job (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT      NOT NULL,
    type            VARCHAR(32) NOT NULL,
    biz_id          BIGINT,
    status          VARCHAR(24) NOT NULL,
    progress        INT         NOT NULL,
    stage           VARCHAR(64),
    message         VARCHAR(512),
    error_code      VARCHAR(64),
    retry_count     INT         NOT NULL,
    idempotency_key VARCHAR(128),
    created_at      DATETIME    NOT NULL,
    updated_at      DATETIME    NOT NULL,
    started_at      DATETIME,
    finished_at     DATETIME,
    version         INT         NOT NULL
);
CREATE UNIQUE INDEX uk_job_idem ON job (idempotency_key);
CREATE INDEX idx_job_user_status ON job (user_id, status, created_at);
CREATE INDEX idx_job_status ON job (status, updated_at);

CREATE TABLE plan_result (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id        BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    program_id    BIGINT       NOT NULL,
    plan_index    INT          NOT NULL,
    total_credits DECIMAL(6,1) NOT NULL,
    risk_score    INT          NOT NULL,
    plan_json     TEXT         NOT NULL,
    created_at    DATETIME     NOT NULL
);
CREATE INDEX idx_plan_job ON plan_result (job_id, plan_index);

CREATE TABLE llm_call_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT,
    job_id            BIGINT,
    scene             VARCHAR(32)  NOT NULL,
    model             VARCHAR(64)  NOT NULL,
    prompt_tokens     INT          NOT NULL,
    completion_tokens INT          NOT NULL,
    cost_micros       BIGINT       NOT NULL,
    latency_ms        INT          NOT NULL,
    success           BOOLEAN      NOT NULL,
    error_message     VARCHAR(512),
    created_at        DATETIME     NOT NULL
);
CREATE INDEX idx_llm_user ON llm_call_log (user_id, created_at);

CREATE TABLE qa_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    program_id BIGINT,
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    citations  TEXT,
    created_at DATETIME    NOT NULL
);
CREATE INDEX idx_qa_user ON qa_message (user_id, program_id, created_at);
