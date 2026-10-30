package com.lingkan.calculation;

import com.lingkan.dividend.entity.StockShareEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 股本换算（spec 010 §3.1）。
 *
 * <p>历史分红之后若发生送股/转增/拆股/并股，当时的每股现金分红需要换算为
 * 当前股本口径，才能与今天的股价对比计算股息率：
 *
 * <pre>
 * adjusted = cash × Π(1 / (1 + bonus_ratio + transfer_ratio))   // BONUS / TRANSFER 逐事件相乘
 *                  × Π(1 / split_ratio)                          // SPLIT（1 拆 N → split_ratio = N）
 *                  × Π(1 / split_ratio)                          // REVERSE_SPLIT（N 并 1 → split_ratio = 1/N）
 * </pre>
 *
 * <p>比例口径（{@link StockShareEvent} 注释）：送转比例为「每 10 股送/转 X 股 → X/10」；
 * 拆股为「1 拆 N → N」；并股为「N 并 1 → 1/N」。拆/并两类的 split_ratio 统一表示
 * 「拆后股数 / 拆前股数」，因此换算因子同为 1/split_ratio。
 *
 * <p>事件窗口：{@code event_date >= 分红除息日}（同日送转视为按除息前股本派息，需折减）。
 * 除息日缺失时无法确定事件先后，不应用任何事件（保守不折减，见 spec 010 已知限制）。
 * 无事件时因子为 1，直接返回原值。任何一步不合法（比例为空/非正）则该事件按因子 1 跳过。
 */
public final class ShareBasisAdjuster {

    private ShareBasisAdjuster() {
    }

    /**
     * 把每股现金分红换算为当前股本口径。
     *
     * @param cashPerShare   每股现金分红（税前）
     * @param exDividendDate 该笔分红的除息日；为 null 时不应用任何事件
     * @param events         该股票全部股本变动事件（任意顺序，内部按日期过滤）
     * @return 换算后的每股现金分红；入参为空时返回 null
     */
    public static BigDecimal adjust(BigDecimal cashPerShare, LocalDate exDividendDate,
                                    List<StockShareEvent> events) {
        if (cashPerShare == null) {
            return null;
        }
        if (exDividendDate == null || events == null || events.isEmpty()) {
            return cashPerShare;
        }
        BigDecimal adjusted = cashPerShare;
        for (StockShareEvent event : events) {
            if (event == null || event.getEventDate() == null
                    || event.getEventDate().isBefore(exDividendDate)) {
                continue;
            }
            BigDecimal factor = factorOf(event);
            if (factor == null) {
                continue;
            }
            adjusted = adjusted.multiply(factor, com.lingkan.common.util.DecimalUtils.MC);
        }
        return adjusted;
    }

    /**
     * 单个事件的换算因子；事件类型未知或比例非法返回 null（按因子 1 处理）。
     */
    private static BigDecimal factorOf(StockShareEvent event) {
        String type = event.getEventType();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case StockShareEvent.TYPE_BONUS -> reciprocalOnePlus(event.getBonusRatio());
            case StockShareEvent.TYPE_TRANSFER -> reciprocalOnePlus(event.getTransferRatio());
            case StockShareEvent.TYPE_SPLIT, StockShareEvent.TYPE_REVERSE_SPLIT ->
                    reciprocal(event.getSplitRatio());
            default -> null;
        };
    }

    /** 1 / (1 + r)；r 为空或非正时返回 null（该事件不折减） */
    private static BigDecimal reciprocalOnePlus(BigDecimal ratio) {
        if (ratio == null || ratio.signum() <= 0) {
            return null;
        }
        return BigDecimal.ONE.divide(BigDecimal.ONE.add(ratio),
                com.lingkan.common.util.DecimalUtils.MC);
    }

    /** 1 / r；r 为空或非正时返回 null */
    private static BigDecimal reciprocal(BigDecimal ratio) {
        if (ratio == null || ratio.signum() <= 0) {
            return null;
        }
        return BigDecimal.ONE.divide(ratio, com.lingkan.common.util.DecimalUtils.MC);
    }
}
