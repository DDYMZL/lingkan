-- V5: 首页看板 - 分红字段补充 + 股本变动事件表
-- 依据：docs/specs/010-home-board/design.md 第二节
-- 说明：
--   1. 只新增结构与补充口径说明，不修改 V1~V4 已执行内容；
--   2. openGauss 语法：不使用 ON CONFLICT，去重由应用层先查后插；
--   3. event_type 枚举：BONUS(送股) / TRANSFER(转增) / SPLIT(拆股) / REVERSE_SPLIT(并股)，
--      送转比例为"每10股送/转X股 -> X/10"，split_ratio 为"1拆N -> N"。

-- 1. 分红表补充：方案唯一标识 + 实际派息实施日期
ALTER TABLE stock_dividends ADD COLUMN IF NOT EXISTS dividend_id         VARCHAR(64);
ALTER TABLE stock_dividends ADD COLUMN IF NOT EXISTS implementation_date DATE;

CREATE INDEX IF NOT EXISTS idx_dividends_dividend_id ON stock_dividends (dividend_id);
CREATE INDEX IF NOT EXISTS idx_dividends_impl_date ON stock_dividends (stock_id, implementation_date);

-- 2. 股本变动事件表（历史分红换算当前股本口径的依据）
CREATE TABLE IF NOT EXISTS stock_share_events (
    id             BIGSERIAL     PRIMARY KEY,
    stock_id       BIGINT        NOT NULL,
    event_date     DATE          NOT NULL,
    event_type     VARCHAR(16)   NOT NULL,
    bonus_ratio    NUMERIC(20,6),
    transfer_ratio NUMERIC(20,6),
    split_ratio    NUMERIC(20,6),
    source_code    VARCHAR(32)   NOT NULL,
    created_at     TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_share_events UNIQUE (stock_id, event_date, event_type, source_code)
);
CREATE INDEX IF NOT EXISTS idx_share_events_stock_date ON stock_share_events (stock_id, event_date);

-- 3. 股息率 TTM 口径说明补充（与 spec 010 第三节一致）
UPDATE metric_definitions
   SET pending_questions = 'TTM窗口按 implementation_date 筛选（缺失时 ex_dividend_date 兜底）；历史分红按 stock_share_events 换算当前股本口径；需覆盖年度分红、中期分红、多次分红；未实施分红不计入；送股转增不计入现金股息'
 WHERE metric_code = 'dividend_yield_ttm';
