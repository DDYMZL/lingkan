package com.lingkan.calculation;

import com.lingkan.common.util.DecimalUtils;
import com.lingkan.stock.entity.StockWeeklyQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 周线技术指标计算器（spec 010 §3.6 / spec 007 Q7-2、Q7-3、Q7-4、Q7-6、Q7-7、Q7-8）。
 *
 * <p>五个指标的口径（用户 2026-10-31 确认）：
 * <ul>
 *   <li><b>60周 / 120周</b>：{@code MA(周线收盘价, maShort)} / {@code MA(周线收盘价, maLong)}，
 *       默认 60 / 120，周期来自配置（{@code lingkan.board.weekly.ma-short|ma-long}），不写死；</li>
 *   <li><b>周中</b>：周线布林中轨 {@code MA(周线收盘价, bollN)}，默认 N=20；</li>
 *   <li><b>周上 / 周下</b>：{@code 周中 ± bollK × STD(周线收盘价, bollN)}，默认 K=2；
 *       STD 为<b>样本标准差（除以 N−1）</b>，对齐通达信/同花顺的 {@code STD}。</li>
 * </ul>
 *
 * <p>周线数据为东财周 K（{@code klt=102}）<b>前复权</b>（{@code fqt=1}），直接采用数据源返回的周线，
 * 不按自然周自行聚合日线；{@code trade_date} 为该周最后一个交易日。
 *
 * <p><b>本周未收盘参与计算</b>（Q7-8）：若最新周线日期与行情时间同属一个 ISO 周，
 * 用行情最新价覆盖该根收盘价后再算，使五个指标随行情变化；不同 ISO 周则不覆盖，
 * 避免用今天的价覆盖上周的线。周末/停牌时行情价即上周收盘价，覆盖为等值操作，无副作用。
 *
 * <p>异常防护（铁律 1/11）：
 * <ul>
 *   <li>可用根数 &lt; 指标所需根数 → 该指标 null（{@code INSUFFICIENT}），禁止用已有根数凑算；</li>
 *   <li>指标窗口内任一收盘价 ≤ 0 → 该窗口指标全部 null。东财前复权对累计分红极大的老股
 *       会产生负价（实测茅台 2001 年周线 −312.68），此时任何均值/标准差都无经济意义；</li>
 *   <li>无周线数据 → {@code MISSING}；</li>
 *   <li>全部输出 null，绝不用 0 冒充。</li>
 * </ul>
 *
 * <p>纯计算、无 Spring 依赖、可独立单元测试。数值一律 BigDecimal，
 * 中间过程用 {@link DecimalUtils#MC}（16 位）精度，仅输出四舍五入到 2 位。
 */
public final class WeeklyIndicatorCalculator {

    /** 输出精度：价格类指标统一 2 位小数 */
    private static final int OUTPUT_SCALE = 2;

    private WeeklyIndicatorCalculator() {
    }

    /** 周线数据状态：正常 / 根数或数据异常导致不可计算 / 无周线数据 */
    public enum WeeklyStatus {
        /** 至少一个指标算出 */
        NORMAL,
        /** 有周线但根数不足，或窗口内收盘价非正（前复权负价） */
        INSUFFICIENT,
        /** 库内无该股周线（未同步或数据源失败） */
        MISSING
    }

    /**
     * 周期参数。全部来自配置（{@code lingkan.board.weekly.*}），改数值不改代码（铁律 2）。
     *
     * @param maShort 短周期均线根数（用户口语「60周」），默认 60
     * @param maLong  长周期均线根数（用户口语「120周」），默认 120
     * @param bollN   布林周期 N，默认 20
     * @param bollK   布林带宽倍数 K，默认 2
     */
    public record Params(int maShort, int maLong, int bollN, BigDecimal bollK) {

        /** 默认参数：MA60 / MA120 / BOLL(20, 2) */
        public static final Params DEFAULT = new Params(60, 120, 20, BigDecimal.valueOf(2));
    }

    /**
     * 计算结果。
     *
     * @param maShort     短周期周均线（默认 60 周）；不可计算为 null
     * @param maLong      长周期周均线（默认 120 周）；不可计算为 null
     * @param bollUpper   周线布林上轨（周上）
     * @param bollMiddle  周线布林中轨（周中）
     * @param bollLower   周线布林下轨（周下）
     * @param status      数据状态
     * @param reason      不可计算原因（追溯用，status = NORMAL 时为 null）
     * @param barCount    参与计算的有效周线根数（含本周覆盖后的那根）
     * @param lastBarDate 最新周线所属周末交易日（数据时效留痕，铁律 4）
     */
    public record WeeklyIndicators(BigDecimal maShort, BigDecimal maLong,
                                   BigDecimal bollUpper, BigDecimal bollMiddle, BigDecimal bollLower,
                                   WeeklyStatus status, String reason,
                                   int barCount, LocalDate lastBarDate) {

        /** 无数据结果（全部 null + MISSING） */
        static WeeklyIndicators missing(String reason) {
            return new WeeklyIndicators(null, null, null, null, null,
                    WeeklyStatus.MISSING, reason, 0, null);
        }
    }

    /** 不带实时价覆盖的计算入口（历史回看、单测用）。 */
    public static WeeklyIndicators calculate(List<StockWeeklyQuote> bars, Params params) {
        return calculate(bars, null, null, params);
    }

    /**
     * 计算五个周线指标。
     *
     * @param bars      周线记录（顺序任意，内部按 trade_date 升序排序；null 日期/收盘价被剔除）
     * @param livePrice 行情最新价，用于覆盖本周未收盘周线的收盘价；null 或非正则不覆盖
     * @param quoteTime 行情时间；与最新周线日期同属一个 ISO 周时才覆盖
     * @param params    周期参数；null 时用 {@link Params#DEFAULT}
     */
    public static WeeklyIndicators calculate(List<StockWeeklyQuote> bars, BigDecimal livePrice,
                                             LocalDateTime quoteTime, Params params) {
        if (bars == null || bars.isEmpty()) {
            return WeeklyIndicators.missing("无周线数据");
        }
        Params p = params == null ? Params.DEFAULT : params;

        List<StockWeeklyQuote> valid = new ArrayList<>();
        for (StockWeeklyQuote bar : bars) {
            if (bar != null && bar.getTradeDate() != null && bar.getClosePrice() != null) {
                valid.add(bar);
            }
        }
        if (valid.isEmpty()) {
            return WeeklyIndicators.missing("周线记录缺少日期或收盘价");
        }
        valid.sort(Comparator.comparing(StockWeeklyQuote::getTradeDate));

        List<BigDecimal> closes = new ArrayList<>(valid.size());
        valid.forEach(bar -> closes.add(bar.getClosePrice()));
        LocalDate lastBarDate = valid.getLast().getTradeDate();

        // 本周未收盘：最新周线与行情同属一个 ISO 周时，用实时价覆盖该根收盘价
        if (livePrice != null && livePrice.signum() > 0 && quoteTime != null
                && sameIsoWeek(lastBarDate, quoteTime.toLocalDate())) {
            closes.set(closes.size() - 1, livePrice);
        }

        BigDecimal maShort = average(tail(closes, p.maShort()), p.maShort());
        BigDecimal maLong = average(tail(closes, p.maLong()), p.maLong());
        Boll boll = boll(tail(closes, p.bollN()), p.bollN(), p.bollK());

        boolean anyComputed = maShort != null || maLong != null || boll != null;
        if (anyComputed) {
            return new WeeklyIndicators(maShort, maLong,
                    boll == null ? null : boll.upper(),
                    boll == null ? null : boll.middle(),
                    boll == null ? null : boll.lower(),
                    WeeklyStatus.NORMAL, null, closes.size(), lastBarDate);
        }
        return new WeeklyIndicators(null, null, null, null, null, WeeklyStatus.INSUFFICIENT,
                insufficientReason(closes.size(), p), closes.size(), lastBarDate);
    }

    // ==================== 内部计算 ====================

    private record Boll(BigDecimal upper, BigDecimal middle, BigDecimal lower) {
    }

    /** 取序列末尾 n 个元素；不足 n 时返回全部（由调用方按根数判定不可计算）。 */
    private static List<BigDecimal> tail(List<BigDecimal> closes, int n) {
        if (n <= 0 || closes.size() <= n) {
            return closes;
        }
        return closes.subList(closes.size() - n, closes.size());
    }

    /**
     * 算术平均（HALF_UP 2 位）。
     *
     * @return 根数不足 n、或窗口内任一收盘价 ≤ 0（前复权负价）时返回 null
     */
    private static BigDecimal average(List<BigDecimal> window, int n) {
        if (n <= 0 || window.size() < n || hasNonPositive(window)) {
            return null;
        }
        return round(mean(window));
    }

    /**
     * 布林轨：中轨 = MA(N)，上/下轨 = 中轨 ± K × 样本标准差（除以 N−1）。
     * 中间过程保留 16 位精度，仅输出四舍五入到 2 位（与通达信显示口径一致）。
     */
    private static Boll boll(List<BigDecimal> window, int n, BigDecimal k) {
        if (n < 2 || window.size() < n || hasNonPositive(window) || k == null) {
            return null;
        }
        BigDecimal mean = mean(window);
        BigDecimal sumSquare = BigDecimal.ZERO;
        for (BigDecimal close : window) {
            BigDecimal diff = close.subtract(mean);
            sumSquare = sumSquare.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSquare.divide(BigDecimal.valueOf(n - 1L), DecimalUtils.MC);
        BigDecimal sigma = variance.sqrt(DecimalUtils.MC);
        BigDecimal band = k.multiply(sigma);
        return new Boll(round(mean.add(band)), round(mean), round(mean.subtract(band)));
    }

    private static BigDecimal mean(List<BigDecimal> window) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : window) {
            sum = sum.add(value);
        }
        return sum.divide(BigDecimal.valueOf(window.size()), DecimalUtils.MC);
    }

    private static boolean hasNonPositive(List<BigDecimal> window) {
        for (BigDecimal value : window) {
            if (value.signum() <= 0) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    /** ISO 周比较：同时比对周所属年与周序号，跨年同周（如 12-29 与 01-01）也算同一周。 */
    private static boolean sameIsoWeek(LocalDate left, LocalDate right) {
        WeekFields iso = WeekFields.ISO;
        return left.get(iso.weekBasedYear()) == right.get(iso.weekBasedYear())
                && left.get(iso.weekOfWeekBasedYear()) == right.get(iso.weekOfWeekBasedYear());
    }

    /**
     * 不可计算原因（追溯用）。
     *
     * <p>走到这里说明五个指标全为 null：若可用根数连最短周期都不够，则原因是根数不足；
     * 否则至少有一个指标根数是够的，它仍为 null 只可能是窗口内收盘价非正（前复权负价）。
     */
    private static String insufficientReason(int barCount, Params p) {
        int required = Math.min(Math.min(p.maShort(), p.maLong()), p.bollN());
        if (required <= 0) {
            return "周期参数非法：ma-short/ma-long/boll-n 必须为正整数";
        }
        if (barCount < required) {
            return "周线根数不足：可用 " + barCount + " 根，最少需要 " + required
                    + " 根（MA" + p.maShort() + " / MA" + p.maLong() + " / BOLL" + p.bollN() + "）";
        }
        return "周线数据异常（前复权收盘价非正），不输出均值与布林轨";
    }
}
