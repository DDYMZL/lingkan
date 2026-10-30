package com.lingkan.datasource.provider.impl;

import com.lingkan.datasource.provider.dto.DividendItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 东财分红接口解析测试。样本为 2026-10-30 真实请求返回的片段，
 * 字段口径与换算规则以实测为准，禁止编造。
 */
class EastMoneyDividendProviderTest {

    @Test
    @DisplayName("纯现金分红：每10股派息换算为每股（茅台 2026-06-26 除息）")
    void parseCashDividend() {
        String body = """
                {"result":{"pages":6,"data":[{
                  "SECUCODE":"600519.SH","SECURITY_NAME_ABBR":"贵州茅台","SECURITY_CODE":"600519",
                  "BONUS_IT_RATIO":null,"BONUS_RATIO":null,"IT_RATIO":null,
                  "PRETAX_BONUS_RMB":280.2423,
                  "PLAN_NOTICE_DATE":"2026-04-17 00:00:00",
                  "EQUITY_RECORD_DATE":"2026-06-25 00:00:00",
                  "EX_DIVIDEND_DATE":"2026-06-26 00:00:00",
                  "REPORT_DATE":"2025-12-31 00:00:00",
                  "ASSIGN_PROGRESS":"实施分配",
                  "IMPL_PLAN_PROFILE":"10派280.2423元(含税)"
                }]}}
                """;
        List<DividendItem> items = EastMoneyDividendProvider.parse("600519", body);
        assertEquals(1, items.size());
        DividendItem item = items.getFirst();

        assertEquals("600519", item.stockCode());
        assertEquals(0, new BigDecimal("28.02423").compareTo(item.cashDividendPerShare()));
        assertEquals("2025", item.fiscalYear());
        assertEquals(LocalDate.of(2026, 6, 26), item.exDividendDate());
        assertEquals(LocalDate.of(2026, 6, 25), item.recordDate());
        assertEquals(LocalDate.of(2026, 4, 17), item.announcementDate());
        assertEquals("实施分配", item.planStatus());
        assertNull(item.bonusRatio());
        assertNull(item.transferRatio());
        assertNull(item.paymentDate());
        assertNull(item.implementationDate());
        assertEquals("eastmoney:600519.SH:2025-12-31 00:00:00:2026-06-26", item.dividendId());
    }

    @Test
    @DisplayName("现金分红+转增：10转4派10元 → 每股1元、转增0.4（新易盛）")
    void parseTransferWithCash() {
        String body = """
                {"result":{"pages":4,"data":[{
                  "SECUCODE":"300502.SZ","SECURITY_NAME_ABBR":"新易盛","SECURITY_CODE":"300502",
                  "BONUS_IT_RATIO":4,"BONUS_RATIO":null,"IT_RATIO":4,
                  "PRETAX_BONUS_RMB":10,
                  "PLAN_NOTICE_DATE":"2026-04-24 00:00:00",
                  "EQUITY_RECORD_DATE":"2026-06-10 00:00:00",
                  "EX_DIVIDEND_DATE":"2026-06-11 00:00:00",
                  "REPORT_DATE":"2025-12-31 00:00:00",
                  "ASSIGN_PROGRESS":"实施分配",
                  "IMPL_PLAN_PROFILE":"10转4股派10.00元(含税,扣税后9.00元)"
                }]}}
                """;
        List<DividendItem> items = EastMoneyDividendProvider.parse("300502", body);
        assertEquals(1, items.size());
        DividendItem item = items.getFirst();

        assertEquals(0, BigDecimal.ONE.compareTo(item.cashDividendPerShare()));
        assertEquals(0, new BigDecimal("0.4").compareTo(item.transferRatio()));
        assertNull(item.bonusRatio());
        assertEquals(LocalDate.of(2026, 6, 11), item.exDividendDate());
    }

    @Test
    @DisplayName("IT_RATIO 缺失时用 BONUS_IT_RATIO（送+转合计）兜底")
    void parseTransferFallback() {
        String body = """
                {"result":{"pages":1,"data":[{
                  "SECUCODE":"000001.SZ","SECURITY_CODE":"000001",
                  "BONUS_IT_RATIO":6,"BONUS_RATIO":2,"IT_RATIO":null,
                  "PRETAX_BONUS_RMB":15,
                  "PLAN_NOTICE_DATE":"2025-04-01 00:00:00",
                  "EQUITY_RECORD_DATE":"2025-05-01 00:00:00",
                  "EX_DIVIDEND_DATE":"2025-05-02 00:00:00",
                  "REPORT_DATE":"2024-12-31 00:00:00",
                  "ASSIGN_PROGRESS":"实施分配",
                  "IMPL_PLAN_PROFILE":"10送2股转4股派15.00元"
                }]}}
                """;
        List<DividendItem> items = EastMoneyDividendProvider.parse("000001", body);
        DividendItem item = items.getFirst();

        assertEquals(0, new BigDecimal("0.2").compareTo(item.bonusRatio()));
        // 6（送+转合计）- 2（送）= 4 → 转增 0.4
        assertEquals(0, new BigDecimal("0.4").compareTo(item.transferRatio()));
    }

    @Test
    @DisplayName("数据源无记录（result 为空）返回空列表")
    void parseEmptyResult() {
        String body = "{\"version\":null,\"result\":null,\"success\":false,\"message\":\"暂无数据\",\"code\":0}";
        assertTrue(EastMoneyDividendProvider.parse("600519", body).isEmpty());
    }

    @Test
    @DisplayName("非法报文返回空列表而不是抛异常")
    void parseInvalidBody() {
        assertTrue(EastMoneyDividendProvider.parse("600519", "not-a-json").isEmpty());
    }
}
