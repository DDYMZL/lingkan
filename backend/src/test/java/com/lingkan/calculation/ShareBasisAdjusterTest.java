package com.lingkan.calculation;

import com.lingkan.dividend.entity.StockShareEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 股本换算单元测试（spec 010 §3.1 / 设计 §八 ShareBasisAdjusterTest）。
 *
 * <p>比例口径：送转为「每 10 股送/转 X 股 → X/10」；拆股 1 拆 N → N；并股 N 并 1 → 1/N。
 */
class ShareBasisAdjusterTest {

    private static final LocalDate EX = LocalDate.of(2026, 6, 15);

    private static StockShareEvent event(LocalDate date, String type,
                                         BigDecimal bonus, BigDecimal transfer,
                                         BigDecimal split) {
        StockShareEvent e = new StockShareEvent();
        e.setEventDate(date);
        e.setEventType(type);
        e.setBonusRatio(bonus);
        e.setTransferRatio(transfer);
        e.setSplitRatio(split);
        return e;
    }

    @Test
    @DisplayName("10 送 10：3 元 → 1.5 元口径")
    void bonusTenForTen() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(event(EX, "BONUS", new BigDecimal("1.0"), null, null)));
        assertEquals(new BigDecimal("1.5"), round(adjusted));
    }

    @Test
    @DisplayName("10 转 5：3 元 → 2 元口径")
    void transferFiveForTen() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(event(EX, "TRANSFER", null, new BigDecimal("0.5"), null)));
        assertEquals(new BigDecimal("2"), round(adjusted));
    }

    @Test
    @DisplayName("送+转叠加：因子连乘（10送5 + 10转5 → ÷2.25）")
    void bonusAndTransferStack() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(
                        event(EX, "BONUS", new BigDecimal("0.5"), null, null),
                        event(EX, "TRANSFER", null, new BigDecimal("0.5"), null)));
        assertEquals(new BigDecimal("1.3333"), round(adjusted));
    }

    @Test
    @DisplayName("拆股 1 拆 2：3 元 → 1.5 元口径")
    void splitOneToTwo() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(event(EX, "SPLIT", null, null, new BigDecimal("2"))));
        assertEquals(new BigDecimal("1.5"), round(adjusted));
    }

    @Test
    @DisplayName("并股 2 并 1（split_ratio=0.5）：3 元 → 6 元口径")
    void reverseSplitTwoToOne() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(event(EX, "REVERSE_SPLIT", null, null, new BigDecimal("0.5"))));
        assertEquals(new BigDecimal("6"), round(adjusted));
    }

    @Test
    @DisplayName("除息日当日的事件参与换算")
    void eventOnSameDayApplies() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(event(EX, "BONUS", new BigDecimal("1.0"), null, null)));
        assertEquals(new BigDecimal("1.5"), round(adjusted));
    }

    @Test
    @DisplayName("除息日之前的事件不参与换算")
    void eventBeforeExDateIgnored() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(event(EX.minusDays(1), "BONUS", new BigDecimal("1.0"), null, null)));
        assertEquals(new BigDecimal("3"), round(adjusted));
    }

    @Test
    @DisplayName("无事件：原值返回")
    void noEvents() {
        assertEquals(new BigDecimal("3"),
                round(ShareBasisAdjuster.adjust(new BigDecimal("3"), EX, List.of())));
    }

    @Test
    @DisplayName("除息日缺失：不应用任何事件（保守不折减）")
    void nullExDate() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), null,
                List.of(event(EX, "BONUS", new BigDecimal("1.0"), null, null)));
        assertEquals(new BigDecimal("3"), round(adjusted));
    }

    @Test
    @DisplayName("比例为 0/空/未知类型的事件按因子 1 跳过")
    void invalidEventsSkipped() {
        BigDecimal adjusted = ShareBasisAdjuster.adjust(new BigDecimal("3"), EX,
                List.of(
                        event(EX, "BONUS", null, null, null),
                        event(EX, "TRANSFER", null, BigDecimal.ZERO, null),
                        event(EX, "UNKNOWN", new BigDecimal("1.0"), null, null)));
        assertEquals(new BigDecimal("3"), round(adjusted));
    }

    @Test
    @DisplayName("现金分红为空返回 null")
    void nullCash() {
        assertNull(ShareBasisAdjuster.adjust(null, EX, List.of()));
    }

    private static BigDecimal round(BigDecimal value) {
        return value == null ? null
                : value.setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
