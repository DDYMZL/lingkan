-- V4: 全市场股票数据同步任务表
-- 依据：docs/specs/009-stock-sync/design.md
-- 说明：
--   1. 只新增表，不修改 V1~V3 已执行结构与已有业务表；
--   2. 任务是"同步过程"的审计记录，与 stocks / stock_quotes / stock_metric_values 解耦；
--   3. 字段统计口径见 design.md「任务字段口径」，未列出的拓展字段（stock_added / stock_updated /
--      stock_skipped / current_phase / progress）用于进度展示，不参与业务判断。

-- 1. 同步主任务
CREATE TABLE IF NOT EXISTS stock_sync_task (
    id              BIGSERIAL PRIMARY KEY,
    task_no         VARCHAR(32)  NOT NULL,
    sync_type       VARCHAR(16)  NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    trigger_type    VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    current_phase   VARCHAR(32),
    progress        INT          DEFAULT 0,
    total_stocks    INT          DEFAULT 0,
    success_stocks  INT          DEFAULT 0,
    failed_stocks   INT          DEFAULT 0,
    stock_added     INT          DEFAULT 0,
    stock_updated   INT          DEFAULT 0,
    stock_skipped   INT          DEFAULT 0,
    quote_total     INT          DEFAULT 0,
    quote_success   INT          DEFAULT 0,
    quote_failed    INT          DEFAULT 0,
    metric_total    INT          DEFAULT 0,
    metric_success  INT          DEFAULT 0,
    metric_failed   INT          DEFAULT 0,
    start_time      TIMESTAMP,
    end_time        TIMESTAMP,
    duration_ms     BIGINT,
    error_message   TEXT,
    remark          TEXT,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_sync_task_no UNIQUE (task_no)
);
CREATE INDEX IF NOT EXISTS idx_stock_sync_task_status ON stock_sync_task (status, created_at);

-- 2. 同步明细（失败可追溯、可精准重试）
CREATE TABLE IF NOT EXISTS stock_sync_task_detail (
    id             BIGSERIAL PRIMARY KEY,
    task_id        BIGINT       NOT NULL,
    stock_code     VARCHAR(16),
    provider_code  VARCHAR(32),
    metric_code    VARCHAR(64),
    sync_type      VARCHAR(16),
    status         VARCHAR(16)  NOT NULL,
    retry_count    INT          DEFAULT 0,
    error_message  VARCHAR(512),
    created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_sync_detail_task ON stock_sync_task_detail (task_id, status);
CREATE INDEX IF NOT EXISTS idx_sync_detail_stock ON stock_sync_task_detail (task_id, stock_code);
-- 同一任务内同一 (股票, 数据源, 指标, 类型) 只保留一条，避免重复同步累计大量重复明细
CREATE UNIQUE INDEX IF NOT EXISTS uk_sync_detail_task_item
    ON stock_sync_task_detail (task_id, sync_type, stock_code, provider_code, metric_code);

-- 3. 同步任务互斥锁（数据库级，支持多实例与超时自动过期）
CREATE TABLE IF NOT EXISTS stock_sync_lock (
    lock_name    VARCHAR(64) PRIMARY KEY,
    owner        VARCHAR(128) NOT NULL,
    acquired_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at   TIMESTAMP    NOT NULL,
    remark       VARCHAR(256)
);

-- 4. 迁移占位：股票清单无需独立迁移脚本，stocks 表结构已满足全部字段（V1）
--    行业(industry)、板块(board)、上市状态(stock_status)、ST(is_st)、上市日期(listing_date) 均已存在。
