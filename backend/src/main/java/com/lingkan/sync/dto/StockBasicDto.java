package com.lingkan.sync.dto;

import java.time.LocalDate;

/**
 * 股票基础信息（来自东方财富全市场清单）。
 *
 * <p>字段映射依据 2026-10-30 实测响应：
 * f12=代码、f14=名称、f13=市场(1 沪 / 0 深或北)、f100=所属行业、f26=上市日期、f2=最新价。
 * 交易所不能只看 f13（北交所同为 0），需结合代码前缀判定，见 {@code StockCodeRules}。
 *
 * @param stockCode   股票代码，如 600519
 * @param stockName   股票简称，如 贵州茅台
 * @param exchange    交易所 SH / SZ / BJ
 * @param market      市场中文名：主板 / 创业板 / 科创板 / 北交所（沿用 docs/database/schema.md 口径）
 * @param board       板块枚举 MAIN / STAR / GEM / BSE
 * @param industry    所属行业，数据源缺失时为 null（入库时不得用空值覆盖已有值）
 * @param stockStatus 上市状态，LISTED / DELISTED
 * @param isSt        是否 ST / *ST 股
 * @param listingDate 上市日期，数据源缺失时为 null
 */
public record StockBasicDto(
        String stockCode,
        String stockName,
        String exchange,
        String market,
        String board,
        String industry,
        String stockStatus,
        Boolean isSt,
        LocalDate listingDate
) {

    public static final String STATUS_LISTED = "LISTED";
    public static final String STATUS_DELISTED = "DELISTED";
}
