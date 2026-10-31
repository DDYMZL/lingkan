package com.lingkan.calculation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 首页看板行（spec 010 §3.5 / §6 GET /api/v1/watchlist/board）。
 *
 * <p>数值缺失一律 {@code null}（Jackson 非空序列化 → 字段缺省），前端统一渲染
 * {@code --}；禁止输出 0 冒充。
 *
 * @param stockCode              股票代码
 * @param stockName              股票名称
 * @param exchange               交易所（SH / SZ / BJ）
 * @param industry               所属行业（{@code stocks.industry}，东财 f127）；缺失为 null，前端展示 --
 * @param price                  现价
 * @param changePercent          涨跌幅（%）
 * @param changeAmount           涨跌额（元）
 * @param annualDividendPerShare TTM 每股现金分红（当前股本口径，元）
 * @param dividendYieldTtm       现股息率 TTM（%，2 位小数）
 * @param peTtm                  PE TTM；LOSS 时为 null（前端展示「亏损」）
 * @param peStatus               NORMAL / LOSS / N_A
 * @param ma60w                  60 周均线（MA(周收盘, ma-short)，前复权）；根数不足或数据异常为 null
 * @param ma120w                 120 周均线（MA(周收盘, ma-long)，前复权）；根数不足或数据异常为 null
 * @param weekUpper              周上：周线布林上轨（周中 + K × 样本标准差）
 * @param weekMiddle             周中：周线布林中轨（MA(周收盘, boll-n)）
 * @param weekLower              周下：周线布林下轨（周中 − K × 样本标准差）
 * @param weeklyStatus           周线数据状态 NORMAL / INSUFFICIENT / MISSING
 * @param weeklyDataTime         最新周线所属周末交易日（数据时效留痕，铁律 4）
 * @param targetPrices           各目标档位价格，键为档位字符串（如 "4.0"）；v1.2 起六档
 * @param quoteTime              行情时间（交易所行情 data_time）
 * @param dividendDataTime       分红数据时间（窗口内最新一笔分红生效日）
 * @param financialDataTime      财务数据时间（最近一期财报披露日）
 * @param dataStatus             NORMAL / STALE / SUSPENDED
 */
public record BoardRowDto(
        String stockCode,
        String stockName,
        String exchange,
        String industry,
        BigDecimal price,
        BigDecimal changePercent,
        BigDecimal changeAmount,
        BigDecimal annualDividendPerShare,
        BigDecimal dividendYieldTtm,
        BigDecimal peTtm,
        String peStatus,
        BigDecimal ma60w,
        BigDecimal ma120w,
        BigDecimal weekUpper,
        BigDecimal weekMiddle,
        BigDecimal weekLower,
        String weeklyStatus,
        LocalDate weeklyDataTime,
        Map<String, BigDecimal> targetPrices,
        LocalDateTime quoteTime,
        LocalDate dividendDataTime,
        LocalDate financialDataTime,
        String dataStatus
) {
}
