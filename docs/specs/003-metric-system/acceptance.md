# Spec 003 — 验收标准

## A. 指标元数据

| 编号 | 验收项 | 判定 |
|---|---|---|
| 003-1 | 指标库完整 | `GET /metrics` 返回 74 项，覆盖 6 个分类 |
| 003-2 | 用户指定指标存在 | 存在 `new_low_60d_3d`、`net_profit_parent_yoy`、`week_30`、`week_middle`、`week_60`、`week_lower`、`week_upper`、`band_position` |
| 003-3 | PENDING 指标有明确问题 | 上述周线类与 60 日新低指标 `calculationStatus=PENDING`，`pendingQuestions` 非空 |
| 003-4 | 分类聚合正确 | `GET /metrics/categories` 返回 6 组，每组 metrics 非空 |

## B. 取值行为

| 编号 | 验收项 | 判定 |
|---|---|---|
| 003-5 | PENDING 不输出数值 | 请求 PENDING 指标，返回 `value=null`、`status=PENDING`、`displayValue="--"` |
| 003-6 | 负利润 PE | 归母净利润合计为负时，PE 为负值且 `statusText` 含"亏损" |
| 003-7 | 零利润 PE | 归母净利润合计为 0 时 `status=MISSING`，提示"不可计算" |
| 003-8 | 财务不足四季 | 财务数据 < 4 条时 PE 返回 MISSING，提示"财务数据未采集" |
| 003-9 | 多次分红 | 一年内两次分红，股息率 = 两次之和 ÷ 现价 × 100 |
| 003-10 | 未实施分红 | 预案状态的分红不计入股息率 |
| 003-11 | 缺数据不填 0 | 所有无值场景 `displayValue="--"`，不出现 `0.00` |

## C. 配置生效

| 编号 | 验收项 | 判定 |
|---|---|---|
| 003-12 | 启用/停用即时生效 | 停用 `pe_ttm` 后，列表与详情不再返回该指标 |
| 003-13 | 排序生效 | 调整 sortOrder 后，前端展示顺序同步变化 |
| 003-14 | 精度生效 | 精度改为 4，展示值变为 4 位小数 |
| 003-15 | 展示位置生效 | 关闭"展示在自选"后，自选列表不再出现该指标 |
| 003-16 | 重置可用 | `POST /reset` 后恢复默认 6 项启用 |

## D. 单元测试

| 编号 | 验收项 | 判定 |
|---|---|---|
| 003-17 | 计算测试通过 | `mvn test` 中 `DecimalUtilsTest` 15 个用例全绿 |
