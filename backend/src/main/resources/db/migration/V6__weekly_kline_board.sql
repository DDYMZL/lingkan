-- V6: 首页看板 v1.2 - 周线行情表 + 周线技术指标登记
-- 依据：docs/specs/010-home-board/design.md §2.4、docs/specs/007-history/spec.md（Q7-2/3/4/6/7/8 裁决）
-- 说明：
--   1. 只新增结构与更新指标登记，不修改 V1~V5 已执行内容，不删除任何历史指标行（铁律 5）；
--   2. openGauss 语法：不使用 ON CONFLICT，整窗口覆盖 upsert 由应用层先查后插/更新；
--   3. 周线来源为东财周 K 接口（klt=102）前复权（fqt=1），2026-10-31 真实请求验证；
--      adjustment_type 固定 QFQ，前复权基准随最新除权事件漂移，故每次同步整窗口覆盖；
--   4. week_30 / band_position 口径仍未确认（Q7-1 / Q7-5），保持 PENDING 不参与计算。

-- 1. 周线行情表（前复权，trace_date 为该周最后一个交易日）
CREATE TABLE IF NOT EXISTS stock_weekly_quotes (
    id              BIGSERIAL     PRIMARY KEY,
    stock_id        BIGINT        NOT NULL,
    trade_date      DATE          NOT NULL,
    open_price      NUMERIC(20,4),
    close_price     NUMERIC(20,4),
    high_price      NUMERIC(20,4),
    low_price       NUMERIC(20,4),
    volume          NUMERIC(24,4),
    amount          NUMERIC(24,4),
    adjustment_type VARCHAR(16)   NOT NULL,
    source_code     VARCHAR(32)   NOT NULL,
    fetch_time      TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_weekly_quotes UNIQUE (stock_id, trade_date, adjustment_type, source_code)
);
CREATE INDEX IF NOT EXISTS idx_weekly_quotes_stock_date ON stock_weekly_quotes (stock_id, trade_date);

-- 2. 新增指标登记：120 周均线（用户口语「120周」，V2 未登记）
INSERT INTO metric_definitions
(metric_code, metric_name, metric_category, data_type, unit, description,
 calculation_type, calculation_expression, calculation_status, enabled, default_precision,
 sort_order, pending_questions)
VALUES
('week_120', '周120', 'technical', 'DECIMAL', '元', '120周收盘价简单移动平均（前复权周线）',
 'CALCULATED', 'MA(周线收盘价, 120) 前复权周线；可用周线根数不足 120 时不计算', 'READY', TRUE, 2, 103, NULL);

-- 3. 口径确认（Q7-2/3/4/6/7/8）：四个原 PENDING 指标转 READY 并写入公式
--    pending_questions 由第 4 步统一改写为「已确认口径」留痕（沿用 V5 对 dividend_yield_ttm 的做法）
UPDATE metric_definitions
   SET description            = '60周收盘价简单移动平均（前复权周线，含本周未收盘）',
       calculation_expression = 'MA(周线收盘价, 60) 前复权周线；与 ma_60w 同口径同一实现；可用周线根数不足 60 时不计算',
       calculation_status     = 'READY'
 WHERE metric_code = 'week_60';

UPDATE metric_definitions
   SET description            = '周线布林中轨（前复权周线，含本周未收盘）',
       calculation_expression = 'MA(周线收盘价, 20)；N 默认 20 可配置（lingkan.board.weekly.boll-n）',
       calculation_status     = 'READY'
 WHERE metric_code = 'week_middle';

UPDATE metric_definitions
   SET description            = '周线布林上轨（前复权周线，含本周未收盘）',
       calculation_expression = '周中 + K * STD(周线收盘价, 20)；K 默认 2 可配置，STD 为样本标准差（除以 N-1），对齐通达信/同花顺',
       calculation_status     = 'READY'
 WHERE metric_code = 'week_upper';

UPDATE metric_definitions
   SET description            = '周线布林下轨（前复权周线，含本周未收盘）',
       calculation_expression = '周中 - K * STD(周线收盘价, 20)；K 默认 2 可配置，STD 为样本标准差（除以 N-1），对齐通达信/同花顺',
       calculation_status     = 'READY'
 WHERE metric_code = 'week_lower';

-- 4. 周线共性口径留痕（覆盖原「待确认」问句，状态保持 READY）
UPDATE metric_definitions
   SET pending_questions = '已确认（2026-10-31）：周线取东财 klt=102 前复权 fqt=1，含本周未收盘（本周收盘价用实时价覆盖，仅当最新周线日期与行情时间同属一个 ISO 周）；窗口内任一收盘价 <= 0（前复权负价）时全部指标不输出'
 WHERE metric_code IN ('week_60', 'week_120', 'week_middle', 'week_upper', 'week_lower');
