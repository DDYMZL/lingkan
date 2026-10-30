-- V1: 初始化玲看股息数据库结构
-- 数据库：openGauss，编码 UTF-8，金融数值统一使用 NUMERIC
-- 约束：只新增迁移脚本，禁止修改历史脚本

-- 1. 股票基础信息
CREATE TABLE IF NOT EXISTS stocks (
    id              BIGSERIAL PRIMARY KEY,
    stock_code      VARCHAR(16)  NOT NULL,
    stock_name      VARCHAR(64)  NOT NULL,
    exchange        VARCHAR(8)   NOT NULL,
    market          VARCHAR(16),
    board           VARCHAR(32),
    industry        VARCHAR(64),
    listing_date    DATE,
    delisting_date  DATE,
    stock_status    VARCHAR(16)  DEFAULT 'LISTED',
    is_st           BOOLEAN      DEFAULT FALSE,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_stocks_exchange_code ON stocks (exchange, stock_code);
CREATE INDEX IF NOT EXISTS idx_stocks_code ON stocks (stock_code);
CREATE INDEX IF NOT EXISTS idx_stocks_name ON stocks (stock_name);

-- 2. 最新行情（按数据源各保留一条，便于多源对比）
CREATE TABLE IF NOT EXISTS stock_quotes (
    id                BIGSERIAL PRIMARY KEY,
    stock_id          BIGINT       NOT NULL,
    latest_price      NUMERIC(20,4),
    change_percent    NUMERIC(12,4),
    change_amount     NUMERIC(12,4),
    open_price        NUMERIC(20,4),
    high_price        NUMERIC(20,4),
    low_price         NUMERIC(20,4),
    pre_close         NUMERIC(20,4),
    volume            NUMERIC(24,2),
    amount            NUMERIC(24,4),
    turnover_rate     NUMERIC(12,4),
    total_market_cap  NUMERIC(24,4),
    source_code       VARCHAR(32)  NOT NULL,
    data_time         TIMESTAMP,
    fetch_time        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_stock_quotes UNIQUE (stock_id, source_code)
);
CREATE INDEX IF NOT EXISTS idx_stock_quotes_stock ON stock_quotes (stock_id);

-- 3. 历史日线
CREATE TABLE IF NOT EXISTS stock_daily_quotes (
    id               BIGSERIAL PRIMARY KEY,
    stock_id         BIGINT       NOT NULL,
    trade_date       DATE         NOT NULL,
    open_price       NUMERIC(20,4),
    close_price      NUMERIC(20,4),
    high_price       NUMERIC(20,4),
    low_price        NUMERIC(20,4),
    pre_close        NUMERIC(20,4),
    volume           NUMERIC(24,2),
    amount           NUMERIC(24,4),
    change_percent   NUMERIC(12,4),
    adjustment_type  VARCHAR(8)   DEFAULT 'NONE',
    source_code      VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_daily_quotes UNIQUE (stock_id, trade_date, adjustment_type, source_code)
);
CREATE INDEX IF NOT EXISTS idx_daily_quotes_stock_date ON stock_daily_quotes (stock_id, trade_date);

-- 4. 财务数据
CREATE TABLE IF NOT EXISTS stock_financials (
    id                        BIGSERIAL PRIMARY KEY,
    stock_id                  BIGINT       NOT NULL,
    report_period             VARCHAR(16)  NOT NULL,
    report_type               VARCHAR(24),
    revenue                   NUMERIC(24,4),
    net_profit                NUMERIC(24,4),
    net_profit_parent         NUMERIC(24,4),
    revenue_yoy               NUMERIC(12,4),
    net_profit_yoy            NUMERIC(12,4),
    net_profit_parent_yoy     NUMERIC(12,4),
    non_recurring_profit_yoy  NUMERIC(12,4),
    eps                       NUMERIC(16,6),
    roe                       NUMERIC(12,4),
    publish_date              DATE,
    source_code               VARCHAR(32)  NOT NULL,
    fetch_time                TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_financials UNIQUE (stock_id, report_period, report_type, source_code)
);
CREATE INDEX IF NOT EXISTS idx_financials_stock_period ON stock_financials (stock_id, report_period);

-- 5. 分红记录
CREATE TABLE IF NOT EXISTS stock_dividends (
    id                        BIGSERIAL PRIMARY KEY,
    stock_id                  BIGINT        NOT NULL,
    fiscal_year               VARCHAR(16),
    cash_dividend_per_share   NUMERIC(20,6),
    announcement_date         DATE,
    record_date               DATE,
    ex_dividend_date          DATE,
    payment_date              DATE,
    plan_status               VARCHAR(32),
    source_code               VARCHAR(32)   NOT NULL,
    created_at                TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_dividends UNIQUE (stock_id, fiscal_year, ex_dividend_date, source_code)
);
CREATE INDEX IF NOT EXISTS idx_dividends_stock ON stock_dividends (stock_id, fiscal_year);

-- 6. 指标值（多数据源）
CREATE TABLE IF NOT EXISTS stock_metric_values (
    id                   BIGSERIAL PRIMARY KEY,
    stock_id             BIGINT        NOT NULL,
    metric_code          VARCHAR(64)   NOT NULL,
    source_code          VARCHAR(32)   NOT NULL,
    raw_value            VARCHAR(512),
    normalized_value     NUMERIC(24,6),
    unit                 VARCHAR(16),
    data_time            TIMESTAMP,
    fetch_time           TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    status               VARCHAR(16)   DEFAULT 'OK',
    raw_payload          TEXT,
    calculation_version  VARCHAR(64),
    CONSTRAINT uk_metric_values UNIQUE (stock_id, metric_code, source_code)
);
CREATE INDEX IF NOT EXISTS idx_metric_values_lookup
    ON stock_metric_values (metric_code, stock_id);

-- 7. 每日指标快照
CREATE TABLE IF NOT EXISTS stock_daily_metrics (
    id                   BIGSERIAL PRIMARY KEY,
    stock_id             BIGINT        NOT NULL,
    metric_code          VARCHAR(64)   NOT NULL,
    metric_value         NUMERIC(24,6),
    trade_date           DATE          NOT NULL,
    source_code          VARCHAR(32)   NOT NULL,
    calculation_version  VARCHAR(64),
    created_at           TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_daily_metrics UNIQUE (stock_id, metric_code, trade_date, source_code)
);
CREATE INDEX IF NOT EXISTS idx_daily_metrics_series
    ON stock_daily_metrics (stock_id, metric_code, trade_date);

-- 8. 指标定义
CREATE TABLE IF NOT EXISTS metric_definitions (
    id                       BIGSERIAL PRIMARY KEY,
    metric_code              VARCHAR(64)   NOT NULL,
    metric_name              VARCHAR(64)   NOT NULL,
    metric_category          VARCHAR(24)   NOT NULL,
    data_type                VARCHAR(16)   NOT NULL,
    unit                     VARCHAR(16),
    description              VARCHAR(512),
    calculation_type         VARCHAR(16)   NOT NULL,
    calculation_expression   VARCHAR(1024),
    calculation_status       VARCHAR(16)   NOT NULL,
    enabled                  BOOLEAN       DEFAULT TRUE,
    default_precision        INT           DEFAULT 2,
    sort_order               INT           DEFAULT 0,
    pending_questions        TEXT,
    created_at               TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_metric_definitions UNIQUE (metric_code)
);

-- 9. 指标与数据源绑定
CREATE TABLE IF NOT EXISTS metric_source_bindings (
    id             BIGSERIAL PRIMARY KEY,
    metric_code    VARCHAR(64)  NOT NULL,
    provider_code  VARCHAR(32)  NOT NULL,
    priority       INT          DEFAULT 100,
    enabled        BOOLEAN      DEFAULT TRUE,
    is_primary     BOOLEAN      DEFAULT FALSE,
    timeout_ms     INT          DEFAULT 5000,
    cache_seconds  INT          DEFAULT 60,
    CONSTRAINT uk_metric_binding UNIQUE (metric_code, provider_code)
);
CREATE INDEX IF NOT EXISTS idx_bindings_metric ON metric_source_bindings (metric_code, priority);

-- 10. 数据源定义
CREATE TABLE IF NOT EXISTS data_providers (
    id                   BIGSERIAL PRIMARY KEY,
    provider_code        VARCHAR(32)   NOT NULL,
    provider_name        VARCHAR(64)   NOT NULL,
    provider_type        VARCHAR(32)   NOT NULL,
    base_url             VARCHAR(256),
    enabled              BOOLEAN       DEFAULT FALSE,
    requires_token       BOOLEAN       DEFAULT FALSE,
    request_interval_ms  INT           DEFAULT 1000,
    rate_limit_config    VARCHAR(256),
    health_status        VARCHAR(16)   DEFAULT 'UNKNOWN',
    last_success_at      TIMESTAMP,
    last_failure_at      TIMESTAMP,
    supported_metrics    TEXT,
    description          VARCHAR(512),
    verified             BOOLEAN       DEFAULT FALSE,
    sort_order           INT           DEFAULT 0,
    CONSTRAINT uk_data_providers UNIQUE (provider_code)
);

-- 11. 自选股
CREATE TABLE IF NOT EXISTS user_watchlists (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    stock_id    BIGINT       NOT NULL,
    sort_order  INT          DEFAULT 0,
    remark      VARCHAR(128),
    created_at  TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_watchlist UNIQUE (user_id, stock_id)
);
CREATE INDEX IF NOT EXISTS idx_watchlist_user ON user_watchlists (user_id, sort_order);

-- 12. 用户指标偏好
CREATE TABLE IF NOT EXISTS user_metric_preferences (
    id                BIGSERIAL PRIMARY KEY,
    user_id           VARCHAR(64)  NOT NULL,
    metric_code       VARCHAR(64)  NOT NULL,
    enabled           BOOLEAN      DEFAULT TRUE,
    show_in_home      BOOLEAN      DEFAULT FALSE,
    show_in_watchlist BOOLEAN      DEFAULT FALSE,
    show_in_detail    BOOLEAN      DEFAULT TRUE,
    sort_order        INT          DEFAULT 0,
    selected_source   VARCHAR(32),
    precision         INT,
    display_name      VARCHAR(64),
    unit              VARCHAR(16),
    filterable        BOOLEAN      DEFAULT FALSE,
    sortable          BOOLEAN      DEFAULT TRUE,
    record_history    BOOLEAN      DEFAULT FALSE,
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_metric_pref UNIQUE (user_id, metric_code)
);
CREATE INDEX IF NOT EXISTS idx_user_metric_pref_user ON user_metric_preferences (user_id, sort_order);

-- 13. 数据采集日志
CREATE TABLE IF NOT EXISTS data_sync_logs (
    id             BIGSERIAL PRIMARY KEY,
    provider_code  VARCHAR(32)  NOT NULL,
    metric_code    VARCHAR(64),
    sync_type      VARCHAR(32)  NOT NULL,
    started_at     TIMESTAMP    NOT NULL,
    finished_at    TIMESTAMP,
    total_count    INT          DEFAULT 0,
    success_count  INT          DEFAULT 0,
    failed_count   INT          DEFAULT 0,
    sync_status    VARCHAR(16)  DEFAULT 'RUNNING',
    error_message  TEXT
);
CREATE INDEX IF NOT EXISTS idx_sync_logs_provider ON data_sync_logs (provider_code, started_at);

-- 14. 个人配置
CREATE TABLE IF NOT EXISTS user_settings (
    id             BIGSERIAL PRIMARY KEY,
    user_id        VARCHAR(64)  NOT NULL,
    setting_key    VARCHAR(64)  NOT NULL,
    setting_value  VARCHAR(2048),
    created_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_settings UNIQUE (user_id, setting_key)
);
