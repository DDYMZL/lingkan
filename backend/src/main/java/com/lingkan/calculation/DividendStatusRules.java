package com.lingkan.calculation;

/**
 * 分红方案状态口径（spec 010 §3.2 / §4.3）。
 *
 * <p>东财 {@code ASSIGN_PROGRESS} 实测取值：「实施分配」「预案」等。
 * 口径：状态含「实施」且不含「不实施」视为已实施；
 * TTM 股息率只计入已实施记录，其余（预案/取消/未实施/缺失）一律剔除——
 * 无法确认已实施的记录按「未实施」保守处理，禁止计入 TTM。
 */
public final class DividendStatusRules {

    private DividendStatusRules() {
    }

    /** 已实施：状态文本含「实施」且不含「不实施」。 */
    public static boolean isImplemented(String planStatus) {
        return planStatus != null && !planStatus.isBlank()
                && planStatus.contains("实施")
                && !planStatus.contains("不实施");
    }

    /** TTM 剔除：预案/取消/未实施/状态缺失。 */
    public static boolean isExcludedFromTtm(String planStatus) {
        return !isImplemented(planStatus);
    }
}
