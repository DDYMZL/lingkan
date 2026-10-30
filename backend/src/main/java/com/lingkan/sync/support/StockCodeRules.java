package com.lingkan.sync.support;

import com.lingkan.sync.dto.StockBasicDto;

import java.util.Locale;

/**
 * 股票代码规则：交易所 / 板块 / 市场名称 / ST 判定。
 *
 * <p>为什么不直接使用数据源的 f13 字段：实测 2026-10-30，东方财富 clist 接口 f13 仅区分
 * 1=沪、0=深，北交所（m:0+t:81+s:2048）与深市同为 0，无法区分深市与北交所；
 * 因此以代码前缀为准，f13 仅用于交叉校验（见 {@link #crossCheck(String, Integer)}）。
 */
public final class StockCodeRules {

    public static final String SH = "SH";
    public static final String SZ = "SZ";
    public static final String BJ = "BJ";

    public static final String BOARD_MAIN = "MAIN";
    public static final String BOARD_STAR = "STAR";
    public static final String BOARD_GEM = "GEM";
    public static final String BOARD_BSE = "BSE";

    private StockCodeRules() {
    }

    /** 交易所：6xxxxx=SH，4/8/92 开头=BJ，其余（0/3）=SZ */
    public static String exchangeOf(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return null;
        }
        String code = normalize(stockCode);
        if (code.startsWith("6")) {
            return SH;
        }
        if (code.startsWith("4") || code.startsWith("8") || code.startsWith("92")) {
            return BJ;
        }
        return SZ;
    }

    /** 板块枚举：MAIN / STAR / GEM / BSE */
    public static String boardOf(String stockCode) {
        String exchange = exchangeOf(stockCode);
        String code = normalize(stockCode);
        if (SH.equals(exchange)) {
            return code.startsWith("68") ? BOARD_STAR : BOARD_MAIN;
        }
        if (BJ.equals(exchange)) {
            return BOARD_BSE;
        }
        return code.startsWith("30") ? BOARD_GEM : BOARD_MAIN;
    }

    /** 市场中文名，沿用 docs/database/schema.md 中 market 字段口径 */
    public static String marketOf(String stockCode) {
        return switch (boardOf(stockCode)) {
            case BOARD_STAR -> "科创板";
            case BOARD_GEM -> "创业板";
            case BOARD_BSE -> "北交所";
            default -> "主板";
        };
    }

    /** 名称包含 ST / *ST 视为风险警示股 */
    public static boolean isSt(String stockName) {
        return stockName != null && stockName.toUpperCase(Locale.ROOT).contains("ST");
    }

    /**
     * 数据源市场标识交叉校验：f13=1 必须落在沪市；f13=0 必须是深市或北交所。
     * 不一致说明代码前缀规则或数据源结构发生变化，需要人工确认。
     */
    public static boolean crossCheck(String stockCode, Integer marketFlag) {
        if (marketFlag == null) {
            return true;
        }
        String exchange = exchangeOf(stockCode);
        if (marketFlag == 1) {
            return SH.equals(exchange);
        }
        return SZ.equals(exchange) || BJ.equals(exchange);
    }

    /** 去掉交易所后缀（如 600519.SH）并去除首尾空白 */
    public static String normalize(String stockCode) {
        if (stockCode == null) {
            return null;
        }
        String code = stockCode.trim();
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }

    /** 是否为 A 股代码（6/0/3/4/8/92 开头，6 位数字） */
    public static boolean isAShareCode(String stockCode) {
        String code = normalize(stockCode);
        if (code == null || code.length() != 6 || !code.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return code.startsWith("6") || code.startsWith("0") || code.startsWith("3")
                || code.startsWith("4") || code.startsWith("8") || code.startsWith("9");
    }

    /** 组装基础信息 DTO，统一补齐交易所/板块/市场/ST */
    public static StockBasicDto build(String stockCode, String stockName, String industry,
                                      String status, java.time.LocalDate listingDate) {
        String code = normalize(stockCode);
        return new StockBasicDto(code, cleanName(stockName), exchangeOf(code), marketOf(code),
                boardOf(code), blankToNull(industry), status, isSt(cleanName(stockName)), listingDate);
    }

    /** 股票简称清洗：压缩连续空白（实测存在 "万  科Ａ" 这类多空格名称） */
    public static String cleanName(String stockName) {
        if (stockName == null) {
            return null;
        }
        return stockName.replace('\u3000', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "-".equals(trimmed) ? null : trimmed;
    }
}
