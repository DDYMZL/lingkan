package com.lingkan.datasource.provider.impl;

import com.lingkan.datasource.provider.dto.FinancialItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 东财财报接口解析测试。样本为 2026-10-30 真实请求返回的片段。
 */
class EastMoneyFinancialProviderTest {

    @Test
    @DisplayName("年报与三季报解析：QDATE 报告期 + 累计归母净利润 + report_type 推导")
    void parseQuarterlyAndAnnual() {
        String body = """
                {"result":{"pages":21,"data":[
                  {"SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
                   "REPORTDATE":"2025-12-31 00:00:00","QDATE":"2025Q4",
                   "DATEMMDD":"年报","DATATYPE":"2025年 年报",
                   "TOTAL_OPERATE_INCOME":174144439954.12,
                   "PARENT_NETPROFIT":82320067101.68,
                   "BASIC_EPS":65.66,"WEIGHTAVG_ROE":30.39,
                   "NOTICE_DATE":"2026-04-17 00:00:00"},
                  {"SECURITY_CODE":"600519","SECURITY_NAME_ABBR":"贵州茅台",
                   "REPORTDATE":"2026-09-30 00:00:00","QDATE":"2026Q3",
                   "DATEMMDD":"三季报","DATATYPE":"2026年 三季报",
                   "TOTAL_OPERATE_INCOME":133283652508.95,
                   "PARENT_NETPROFIT":64467425981.95,
                   "BASIC_EPS":51.53,"WEIGHTAVG_ROE":24.47,
                   "NOTICE_DATE":"2026-10-31 00:00:00"}
                ]}}
                """;
        List<FinancialItem> items = EastMoneyFinancialProvider.parse("600519", body);
        assertEquals(2, items.size());

        // 按报告期升序：2025Q4 在前
        FinancialItem annual = items.getFirst();
        assertEquals("2025Q4", annual.reportPeriod());
        assertEquals("ANNUAL", annual.reportType());
        assertEquals(LocalDate.of(2025, 12, 31), annual.reportDate());
        assertEquals(0, new BigDecimal("82320067101.68").compareTo(annual.netProfitParent()));
        assertEquals(0, new BigDecimal("65.66").compareTo(annual.eps()));
        assertEquals(LocalDate.of(2026, 4, 17), annual.publishDate());

        FinancialItem q3 = items.get(1);
        assertEquals("2026Q3", q3.reportPeriod());
        assertEquals("Q3", q3.reportType());
        assertEquals(0, new BigDecimal("64467425981.95").compareTo(q3.netProfitParent()));
        assertEquals(0, new BigDecimal("24.47").compareTo(q3.roe()));
    }

    @Test
    @DisplayName("数据源无记录返回空列表")
    void parseEmptyResult() {
        String body = "{\"version\":null,\"result\":null,\"success\":false,\"message\":\"暂无数据\",\"code\":0}";
        assertTrue(EastMoneyFinancialProvider.parse("600519", body).isEmpty());
    }

    @Test
    @DisplayName("非法报文返回空列表而不是抛异常")
    void parseInvalidBody() {
        assertTrue(EastMoneyFinancialProvider.parse("600519", "not-a-json").isEmpty());
    }
}
