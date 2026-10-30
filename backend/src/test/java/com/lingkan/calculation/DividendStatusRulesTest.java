package com.lingkan.calculation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分红方案状态口径的单元测试（spec 010 §3.2：未实施分红不计入 TTM）。
 */
class DividendStatusRulesTest {

    @Test
    @DisplayName("已实施：状态含「实施」")
    void implemented() {
        assertTrue(DividendStatusRules.isImplemented("实施分配"));
        assertFalse(DividendStatusRules.isExcludedFromTtm("实施分配"));
    }

    @Test
    @DisplayName("预案不计入 TTM")
    void proposedExcluded() {
        assertFalse(DividendStatusRules.isImplemented("预案"));
        assertTrue(DividendStatusRules.isExcludedFromTtm("预案"));
    }

    @Test
    @DisplayName("「不实施」虽含「实施」二字，必须剔除")
    void notImplementedExcluded() {
        assertFalse(DividendStatusRules.isImplemented("不实施分配"));
        assertTrue(DividendStatusRules.isExcludedFromTtm("不实施分配"));
    }

    @Test
    @DisplayName("状态缺失/空白时按未实施保守处理")
    void missingStatusExcluded() {
        assertFalse(DividendStatusRules.isImplemented(null));
        assertFalse(DividendStatusRules.isImplemented(""));
        assertFalse(DividendStatusRules.isImplemented("   "));
        assertTrue(DividendStatusRules.isExcludedFromTtm(null));
        assertTrue(DividendStatusRules.isExcludedFromTtm("取消"));
    }
}
