package com.lingkan.common.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 金融数值计算工具。
 *
 * <p>约定：内部一律使用 BigDecimal 计算，输出按指标精度四舍五入；
 * 无法计算时返回 null，禁止用 0 代替缺失值。
 */
public final class DecimalUtils {

    public static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    public static final int SCALE = 6;

    private DecimalUtils() {
    }

    public static BigDecimal of(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static BigDecimal of(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    public static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, MC);
    }

    /**
     * 同比计算：(本期 - 上年同期) / abs(上年同期) * 100。
     * 上年同期为 0 时不可计算，返回 null（不返回无穷大）。
     */
    public static BigDecimal yoy(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) {
            return null;
        }
        BigDecimal diff = current.subtract(previous);
        return diff.multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), MC);
    }

    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal ratio = divide(numerator, denominator);
        return ratio == null ? null : ratio.multiply(BigDecimal.valueOf(100));
    }

    public static BigDecimal round(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal strip(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.HALF_UP) : stripped;
    }

    /**
     * 带内位置：(价格 - 下轨) / (上轨 - 下轨) * 100
     * 上下轨相等时不可计算。
     */
    public static BigDecimal bandPosition(BigDecimal price, BigDecimal lower, BigDecimal upper) {
        if (price == null || lower == null || upper == null) {
            return null;
        }
        BigDecimal range = upper.subtract(lower);
        if (range.signum() == 0) {
            return null;
        }
        return percent(price.subtract(lower), range);
    }
}
