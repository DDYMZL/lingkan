package com.lingkan.calculation;

import com.lingkan.common.util.DecimalUtils;
import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.entity.StockShareEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股息率 TTM 计算器（spec 010 §3.2）。
 *
 * <p>口径：
 * <ol>
 *   <li>TTM 窗口：以「实施日期」落在 [T-365, T] 内为准（数据源无派息日字段，
 *       实施日期缺失时以除息日兜底）；</li>
 *   <li>状态过滤：只计入已实施（{@link DividendStatusRules}），预案/取消/未实施剔除；</li>
 *   <li>去重：同一 {@code dividend_id} 只计一笔（优先保留有实施日期的记录）；</li>
 *   <li>股本换算：每笔分红经 {@link ShareBasisAdjuster} 换算为当前股本口径后累加；</li>
 *   <li>现股息率 = TTM 每股现金分红 × 100 / 当前股价（HALF_UP 保留 2 位）；
 *       无有效分红或价格为空/0 → null（对外展示 --），禁止用 0 冒充。</li>
 * </ol>
 */
public final class DividendYieldCalculator {

    private DividendYieldCalculator() {
    }

    /**
     * TTM 每股现金分红（当前股本口径）。
     *
     * @param dividends 该股票全部分红记录（任意顺序，内部过滤窗口与状态）
     * @param events    该股票全部股本变动事件
     * @param asOf      计算基准日（通常为今天）
     * @return 结果（含数据日期：窗口内分红的最新实施/除息日）；无有效分红返回 {@code null}
     */
    public static TtmResult ttmCashPerShare(List<StockDividend> dividends,
                                            List<StockShareEvent> events,
                                            LocalDate asOf) {
        if (asOf == null || dividends == null || dividends.isEmpty()) {
            return null;
        }
        LocalDate from = asOf.minusDays(365);
        Map<String, StockDividend> byId = new LinkedHashMap<>();
        List<StockDividend> picked = new ArrayList<>();

        for (StockDividend d : dividends) {
            if (d == null) {
                continue;
            }
            LocalDate effective = effectiveDate(d);
            if (effective == null || effective.isAfter(asOf) || effective.isBefore(from)) {
                continue;
            }
            if (DividendStatusRules.isExcludedFromTtm(d.getPlanStatus())) {
                continue;
            }
            if (d.getCashDividendPerShare() == null || d.getCashDividendPerShare().signum() <= 0) {
                continue;
            }
            if (d.getDividendId() == null || d.getDividendId().isBlank()) {
                picked.add(d);
                continue;
            }
            StockDividend existing = byId.get(d.getDividendId());
            if (existing == null) {
                byId.put(d.getDividendId(), d);
            } else if (existing.getImplementationDate() == null
                    && d.getImplementationDate() != null) {
                byId.put(d.getDividendId(), d);
            }
        }

        List<StockDividend> all = new ArrayList<>(byId.values());
        all.addAll(picked);
        BigDecimal sum = null;
        LocalDate dataTime = null;
        for (StockDividend d : all) {
            BigDecimal adjusted = ShareBasisAdjuster.adjust(
                    d.getCashDividendPerShare(), d.getExDividendDate(), events);
            if (adjusted == null) {
                continue;
            }
            sum = sum == null ? adjusted : sum.add(adjusted);
            LocalDate effective = effectiveDate(d);
            if (effective != null && (dataTime == null || effective.isAfter(dataTime))) {
                dataTime = effective;
            }
        }
        if (sum == null) {
            return null;
        }
        return new TtmResult(sum, dataTime);
    }

    /**
     * 现股息率（%）= 每股现金分红 × 100 / 当前股价，HALF_UP 保留 2 位。
     *
     * @return 价格或分红为空/非正时返回 null
     */
    public static BigDecimal yieldPercent(BigDecimal cashPerShare, BigDecimal price) {
        if (cashPerShare == null || price == null || price.signum() <= 0) {
            return null;
        }
        return DecimalUtils.percent(cashPerShare, price).setScale(2, RoundingMode.HALF_UP);
    }

    /** 分红生效日期：实施日期优先，缺失时除息日兜底 */
    private static LocalDate effectiveDate(StockDividend d) {
        return d.getImplementationDate() != null ? d.getImplementationDate() : d.getExDividendDate();
    }

    /**
     * TTM 计算结果：每股现金分红（当前股本口径）+ 数据日期
     * （窗口内最新一笔分红的生效日期，供看板展示数据时效）。
     */
    public record TtmResult(BigDecimal cashPerShare, LocalDate dataTime) {
    }
}
