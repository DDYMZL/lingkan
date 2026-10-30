# 006-股票详情 · 技术设计

> 版本：v1.0
> 日期：2026-10-30

## 一、后端数据组装

`GET /api/v1/stocks/{stockCode}` 返回 `StockDetailDto`：

```text
StockDetailDto
├── stockCode / stockName / exchange / industry / listingDate
├── quote: QuoteResult            // 来自主数据源（默认腾讯）
├── metrics: Map<String, MetricValueDto>   // 全部启用指标
├── dividends: List<StockDividend>
└── dataStatus: { source, dataTime, fetchTime, status }
```

组装流程（`StockService.detail`）：

```text
1. StockMapper 查 stocks
2. QuoteSnapshotService 取行情（缓存优先）
3. MetricValueService.resolveDetail(stock, quote) 逐指标计算/取值
4. StockDividendMapper 查分红
5. 组装 DTO
```

**关键**：`metrics` 的键由 `user_metric_preferences.enabled` 决定，不含硬编码指标名。新增指标只改配置和指标表，不改 DTO。

## 二、指标解析逻辑（MetricValueService）

按 `metric_definitions.calculation_type` 分三类：

| calculation_type | 处理方式 |
|---|---|
| `SOURCE` | 直接取绑定数据源的 `ProviderResult` |
| `CALCULATED` | 调用对应计算器（`MetricCalculator` 实现） |
| `PENDING` | 返回 `status = NOT_READY`，附带「待配置计算公式」 |

`CALCULATED` 类当前已实现：

| metricCode | 计算器 | 公式 |
|---|---|---|
| `pe_ttm` | `PeCalculator` | 总市值 ÷ 最近四季归母净利润之和；净利润 ≤ 0 → `LOSS` |
| `dividend_yield_ttm` | `DividendYieldCalculator` | 近12个月已实施每股现金分红之和 ÷ 现价 × 100 |
| `profit_yoy` | `ProfitYoyCalculator` | (本期归母 − 上年同期归母) ÷ \|上年同期归母\| × 100 |
| `new_low_60d_3d` | `NewLow60d3dCalculator` | 见 `007-history` |
| `low_60d` / `high_60d` | `RangeCalculator` | 近60个交易日最低/最高 |

所有计算器必须：
1. 接受 `BigDecimal` 输入，使用 `DecimalUtils` 统一精度；
2. 对除零、负值、空值返回明确状态而非抛异常；
3. 输出 `calculationVersion`（见 `CalcVersion`），便于历史口径追溯。

## 三、亏损股 PE 处理

```text
if (netProfitSum == null || netProfitSum.signum() <= 0) {
    return MetricValueDto.ofStatus("LOSS", "亏损或净利润为零，不可计算 PE");
}
```

前端展示：`MetricValue.vue` 检测 `status == LOSS` → 显示「亏损」徽标，不显示数字。

## 四、多源对比浮层

点击指标 → 调用 `GET /stocks/{code}/metrics/{metricCode}/sources` → 弹出 `SourceCompare.vue`：
- 主源置顶并标记「当前选用」
- 其余源显示 `diffFromPrimary`
- 每个源显示 `dataTime` / `fetchTime` / `status`
- 差异超过阈值（价格 0.5%、估值 2%）时高亮提示，**但不自动切换**

## 五、前端结构

```text
pages/stock-detail/index.vue
├── components/stock/StockCard.vue        // 头部 + 基础行情
├── components/metric/MetricValue.vue     // 通用指标渲染（所有指标共用）
├── components/charts/LineChart.vue       // 价格/PE/股息率折线
├── components/datasource/SourceCompare.vue // 多源对比浮层
└── components/common/LkEmpty.vue
```

**禁止**为单个指标写专属卡片组件。`MetricValue` 通过 `metric` 元数据（单位、精度、类型）自行决定渲染方式。

## 六、图表实现

`src/utils/chart.ts` 封装小程序 canvas 2d 初始化：

```ts
const query = uni.createSelectorQuery()
query.select('#chart').fields({ node: true, size: true }).exec((res) => {
  const canvas = res[0].node
  const chart = echarts.init(canvas, null, { width, height, devicePixelRatio: dpr })
  canvas.setChart(chart)
  chart.setOption(option)
})
```

不直接使用 `echarts.init(document.getElementById(...))`——小程序无 DOM。

## 七、性能

- 首屏只请求 `GET /stocks/{code}`；历史图表按需请求。
- 指标计算走 Caffeine 缓存，key = `stockCode + metricCode + sourceCode`，TTL 60s。
- 分红记录 TTL 1 小时（变动频率低）。

## 八、风险

| 风险 | 说明 |
|---|---|
| 图表未实测 | 小程序 canvas 渲染需真机/模拟器验证 |
| 指标数量多导致首屏慢 | 若用户开启 15+ 指标，首屏可能需要 2s+；后续可按 `show_in_detail` 分批 |
| 分红数据口径 | 东方财富分红接口的「每股派息」是否为税前需确认（见 Q6-4） |
