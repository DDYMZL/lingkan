package com.lingkan.calculation;

import com.lingkan.common.util.DecimalUtils;
import com.lingkan.financial.entity.StockFinancial;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PE-TTM 计算器（spec 010 §3.4）。
 *
 * <p>口径：
 * <ol>
 *   <li>TTM 归母净利润 = 最近四个<b>连续</b>季度单季归母净利润之和；
 *       报告期按倒序取 4 并校验期号连续（缺季即不可计算）；</li>
 *   <li>累计值→单季值换算：东财 {@code PARENT_NETPROFIT} 为报告期累计值，
 *       单季 = 本期累计 − 上一报告期累计（Q1 直接用本期值），
 *       <b>禁止直接累加累计值</b>；</li>
 *   <li>PE TTM = 总市值 / TTM 归母净利润；
 *       TTM &gt; 0 → NORMAL；&lt; 0 → LOSS（展示「亏损」）；
 *       = 0、缺失或不可换算 → N_A（null，展示 --）。</li>
 * </ol>
 *
 * <p>总市值取自 {@code QuoteResult.totalMarketCap}（行情快照），行情变化即重算。
 */
public final class PeTtmCalculator {

    /** 报告期格式：YYYYQn（东财 QDATE 口径，年报为 YYYYQ4） */
    private static final Pattern PERIOD = Pattern.compile("^(\\d{4})Q([1-4])$");

    /** 换算单季净利润需要的历史窗口上限：TTM 4 季 + 上一年 Q3（年报单季换算用） */
    private static final int MIN_RECORDS_FOR_TTM = 4;

    private PeTtmCalculator() {
    }

    public enum PeStatus {
        /** 正常计算 */
        NORMAL,
        /** 亏损（TTM 归母净利润为负），展示「亏损」，不参与低 PE 排名 */
        LOSS,
        /** 不可计算（数据缺失/为 0/期号不连续），展示 -- */
        N_A
    }

    /**
     * 计算结果：pe 为总市值/TTM 归母净利润（LOSS 时为负值，供详情页保留原行为；
     * N_A 时为 null）；reason 说明不可计算原因（N_A 时非空）。
     */
    public record PeTtmResult(BigDecimal pe, PeStatus status, String reason, BigDecimal profitTtm) {
    }

    /**
     * 计算 PE-TTM。
     *
     * @param totalMarketCap 总市值（元），来自行情快照
     * @param financials     该股票财务记录（任意顺序；报告期重复时保留 fetchTime 较新者）
     * @return 见 {@link PeTtmResult}
     */
    public static PeTtmResult calculate(BigDecimal totalMarketCap, List<StockFinancial> financials) {
        if (totalMarketCap == null || totalMarketCap.signum() <= 0) {
            return new PeTtmResult(null, PeStatus.N_A, "缺少总市值，无法计算 PE-TTM", null);
        }
        if (financials == null || financials.isEmpty()) {
            return new PeTtmResult(null, PeStatus.N_A, "财务数据未采集，无法计算 PE-TTM", null);
        }

        // 去重（同报告期保留 fetchTime 较新者）+ 倒序
        Map<String, StockFinancial> byPeriod = new LinkedHashMap<>();
        financials.stream()
                .filter(f -> f != null && PERIOD.matcher(
                        f.getReportPeriod() == null ? "" : f.getReportPeriod()).matches())
                .sorted(Comparator
                        .comparing(StockFinancial::getReportPeriod,
                                Comparator.reverseOrder())
                        .thenComparing(StockFinancial::getFetchTime,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(f -> byPeriod.putIfAbsent(f.getReportPeriod(), f));
        List<StockFinancial> sorted = new ArrayList<>(byPeriod.values());

        if (sorted.size() < MIN_RECORDS_FOR_TTM) {
            return new PeTtmResult(null, PeStatus.N_A,
                    "财务数据不足四个季度，无法计算 PE-TTM", null);
        }

        List<StockFinancial> latest = sorted.subList(0, MIN_RECORDS_FOR_TTM);
        // 期号连续校验：相邻报告期必须相差一个季度
        for (int i = 1; i < latest.size(); i++) {
            if (quarterIndex(latest.get(i - 1).getReportPeriod())
                    != quarterIndex(latest.get(i).getReportPeriod()) + 1) {
                return new PeTtmResult(null, PeStatus.N_A,
                        "报告期不连续，无法计算 PE-TTM", null);
            }
        }

        // 累计值 → 单季值
        List<BigDecimal> quarters = new ArrayList<>();
        for (StockFinancial f : latest) {
            BigDecimal single = singleQuarter(f, byPeriod);
            if (single == null) {
                return new PeTtmResult(null, PeStatus.N_A,
                        "缺少上一报告期累计值，无法换算单季净利润", null);
            }
            quarters.add(single);
        }

        BigDecimal ttm = quarters.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (ttm.signum() == 0) {
            return new PeTtmResult(null, PeStatus.N_A, "归母净利润为 0，PE-TTM 不可计算", ttm);
        }
        if (ttm.signum() < 0) {
            return new PeTtmResult(DecimalUtils.divide(totalMarketCap, ttm),
                    PeStatus.LOSS, "亏损", ttm);
        }
        return new PeTtmResult(DecimalUtils.divide(totalMarketCap, ttm),
                PeStatus.NORMAL, null, ttm);
    }

    /**
     * 单季归母净利润 = 本期累计 − 上一报告期累计。
     * Q1 直接用本期值；H1 减同年 Q1；Q3 减同年 H1；年报（Q4）减同年 Q3。
     * 数据不完整（缺少上一期或任一期值为 null）返回 null。
     */
    private static BigDecimal singleQuarter(StockFinancial current,
                                            Map<String, StockFinancial> byPeriod) {
        if (current.getNetProfitParent() == null) {
            return null;
        }
        String type = resolveType(current);
        if ("Q1".equals(type)) {
            return current.getNetProfitParent();
        }
        String prevPeriod = previousPeriod(current.getReportPeriod(), type);
        StockFinancial prev = prevPeriod == null ? null : byPeriod.get(prevPeriod);
        if (prev == null || prev.getNetProfitParent() == null) {
            return null;
        }
        return current.getNetProfitParent().subtract(prev.getNetProfitParent());
    }

    /** 换算类型：优先用库中 report_type（Q1/H1/Q3/ANNUAL），缺失时按期号推导 */
    private static String resolveType(StockFinancial f) {
        String type = f.getReportType();
        if ("Q1".equals(type) || "H1".equals(type) || "Q3".equals(type)
                || "ANNUAL".equals(type)) {
            return type;
        }
        return switch (quarter(f.getReportPeriod())) {
            case 1 -> "Q1";
            case 2 -> "H1";
            case 3 -> "Q3";
            default -> "ANNUAL";
        };
    }

    /** 单季换算所需的上一报告期；类型与期号矛盾时返回 null（不可换算） */
    private static String previousPeriod(String period, String type) {
        Matcher m = PERIOD.matcher(period);
        if (!m.matches()) {
            return null;
        }
        int year = Integer.parseInt(m.group(1));
        int q = Integer.parseInt(m.group(2));
        return switch (type) {
            case "H1" -> q == 2 ? year + "Q1" : null;
            case "Q3" -> q == 3 ? year + "Q2" : null;
            case "ANNUAL" -> q == 4 ? year + "Q3" : null;
            default -> null;
        };
    }

    /** 季度序号：year×4 + (q−1)，用于连续性校验 */
    private static int quarterIndex(String period) {
        Matcher m = PERIOD.matcher(period);
        if (!m.matches()) {
            return Integer.MIN_VALUE;
        }
        return Integer.parseInt(m.group(1)) * 4 + (Integer.parseInt(m.group(2)) - 1);
    }

    private static int quarter(String period) {
        Matcher m = PERIOD.matcher(period);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }
}
