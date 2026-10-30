# 007-历史数据与技术指标 · 技术设计

> 版本：v1.0
> 日期：2026-10-30

## 一、存储设计

### 1.1 `stock_daily_quotes`

```sql
CREATE TABLE stock_daily_quotes (
    id              BIGSERIAL PRIMARY KEY,
    stock_id        BIGINT      NOT NULL,
    trade_date      DATE        NOT NULL,
    open_price      NUMERIC(18,4),
    close_price     NUMERIC(18,4),
    high_price      NUMERIC(18,4),
    low_price       NUMERIC(18,4),
    pre_close       NUMERIC(18,4),
    volume          NUMERIC(24,4),
    amount          NUMERIC(24,4),
    change_percent  NUMERIC(12,4),
    adjustment_type VARCHAR(8)  NOT NULL DEFAULT 'NONE',
    source_code     VARCHAR(32) NOT NULL,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_daily_quote UNIQUE (stock_id, trade_date, adjustment_type, source_code)
);
CREATE INDEX idx_daily_quote_stock_date ON stock_daily_quotes (stock_id, trade_date DESC);
```

`adjustment_type` 取值：`NONE`（不复权）/ `QFQ`（前复权）/ `HFQ`（后复权）。第一版只写入 `NONE`。

### 1.2 `stock_daily_metrics`

```sql
CREATE TABLE stock_daily_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    stock_id            BIGINT      NOT NULL,
    metric_code         VARCHAR(64) NOT NULL,
    metric_value        NUMERIC(24,6),
    trade_date          DATE        NOT NULL,
    source_code         VARCHAR(32) NOT NULL,
    calculation_version VARCHAR(32) NOT NULL,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_daily_metric UNIQUE (stock_id, metric_code, trade_date, source_code)
);
```

**注意**：唯一约束不含 `calculation_version`。口径变更时需要**新增计算版本对应的行还是覆盖**？本项目选择：**沿用同一行，但记录 `calculation_version`，并在 `docs/changes/changelog.md` 登记口径变更历史**。理由：同一交易日的同一指标只应有一个"当前正确值"，历史口径由 changelog + 版本号追溯。此设计与宪法原则六"不可随意覆盖"存在张力，**已记录为待用户确认项 Q7-9**。

## 二、技术指标计算

### 2.1 计算器接口

```java
public interface MetricCalculator {
    String metricCode();
    String calculationVersion();
    MetricValueDto calculate(CalcContext ctx);
}

public record CalcContext(
    Stock stock,
    List<DailyBar> bars,        // 升序，最新在末尾
    QuoteResult quote,
    Map<String, ProviderResult> sources
) {}
```

`TechnicalMetricService` 负责：
1. 按 `stockId` 查最近 N 条日线（N 由各计算器声明）；
2. 校验数据充足性；
3. 派发给对应计算器；
4. 结果写 `stock_daily_metrics`（仅收盘后任务）或直接返回（实时请求）。

### 2.2 `new_low_60d_3d` 实现

```java
int n = bars.size();
if (n < 61) return MetricValueDto.insufficient("可用交易日不足 61 天");

boolean hit = false;
boolean provisional = isTodayBarIncomplete(bars.get(n - 1));

for (int i = n - 3; i < n; i++) {
    if (i < 60) continue;
    BigDecimal cur = bars.get(i).lowPrice();
    BigDecimal ref = bars.subList(i - 60, i).stream()
            .map(DailyBar::lowPrice)
            .min(BigDecimal::compareTo)
            .orElse(null);
    if (ref == null) continue;
    if (cur.compareTo(ref) <= 0) { hit = true; break; }
}

return MetricValueDto.bool(hit, provisional ? "PROVISIONAL" : "SUCCESS");
```

关键点：
- `subList(i - 60, i)` 是**左闭右开**，天然不含当日，符合 spec 的「不含当天」口径。
- `isTodayBarIncomplete` 判断：最新 bar 的 `trade_date == 今天` 且当前时间 < 15:00 → 临时结果。

### 2.3 计算版本

`CalcVersion` 常量类集中管理：

```java
public static final String NEW_LOW_60D_3D = "v1-low60-3d-nonexcl";
```

版本号编码口径信息（`nonexcl` 表示窗口不含当日），便于日后追溯。**任何口径变更必须新建版本常量并写入 changelog。**

## 三、历史序列查询

`HistoryService.series(stockCode, metricCode, period, sourceCode)`：

```text
1. period → startDate 换算（1y/3y/5y/all）
2. 若 metricCode 属于行情类 → 查 stock_daily_quotes（close_price）
3. 若属于计算类 → 查 stock_daily_metrics
4. 若查不到历史但当前可实时计算 → 返回空序列 + status=MISSING，
   并在 message 中说明「历史快照尚未积累」
```

**不允许**用实时值伪造整条历史曲线。这一点是宪法原则五的直接要求。

## 四、收盘快照任务

`QuoteSyncScheduler` 中的收盘任务（默认 `lingkan.sync.enabled=false`）：

```text
每个交易日 15:30
  → 取全部股票列表
  → 分批拉当日日线写入 stock_daily_quotes
  → 对已启用且 show_history=true 的指标计算并写 stock_daily_metrics
  → 写 data_sync_logs
```

## 五、前端

- `LineChart.vue`：通用序列折线，入参 `points[{date, value}]`
- `PeChart.vue` / `DividendChart.vue`：预设 y 轴单位与颜色
- 时间范围切换组件内置于详情页，不单独建页面

## 六、风险

| 风险 | 说明 |
|---|---|
| 历史数据为空 | 首次部署无任何历史快照，趋势图必然为空，需明确提示「需积累数据」 |
| 日线数据源 | 依赖东方财富 K 线接口，字段可能变更 |
| 复权 | 第一版只用不复权，长期分红除权会造成价格跳空，影响 60 日高低判断（待用户确认是否改用前复权） |
