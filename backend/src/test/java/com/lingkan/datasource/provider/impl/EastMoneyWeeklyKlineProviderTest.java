package com.lingkan.datasource.provider.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.datasource.provider.dto.WeeklyBar;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 东财周 K 线解析测试（spec 010 §4.4 / design §八）。
 *
 * <p>样本为 2026-10-31 真实请求 {@code secid=0.000001&klt=102&fqt=1&lmt=8} 的返回片段
 * （平安银行，{@code dktotal=8507}），字段序以实测为准，禁止编造。
 */
class EastMoneyWeeklyKlineProviderTest {

    /** 真实报文：8 根前复权周线，字段序 日期,开盘,收盘,最高,最低,成交量(手),成交额(元),振幅(%) */
    private static final String REAL_BODY = """
            {"rc":0,"rt":4,"svr":2888618116,"lt":1,"full":0,"dlmkts":"","dsc":"",
             "data":{"code":"000001","market":0,"name":"平安银行","decimal":2,
                     "dktotal":8507,"preKPrice":11.64,
                     "klines":[
                       "2026-09-11,11.62,11.49,11.63,11.40,4110191,4831448655.28,1.98",
                       "2026-09-18,11.48,11.45,11.65,11.31,3991089,4682178103.77,2.96",
                       "2026-09-24,11.45,11.30,11.50,11.29,3527557,4080639783.47,1.83",
                       "2026-09-30,11.28,11.57,11.65,11.27,2451678,2801023477.07,3.36",
                       "2026-10-09,11.57,11.59,11.89,11.54,2559792,3003119618.68,3.03",
                       "2026-10-16,11.62,11.50,11.83,11.26,5646271,6524418401.40,4.92",
                       "2026-10-23,11.48,11.20,11.68,11.17,6072885,6923529239.53,4.43",
                       "2026-10-30,11.15,11.64,11.67,11.13,8397163,9584245034.51,4.82"]}}
            """;

    private final EastMoneyWeeklyKlineProvider provider =
            new EastMoneyWeeklyKlineProvider(HttpClient.newHttpClient(), new ObjectMapper());

    @Test
    @DisplayName("真实报文：8 根周线全部解析，字段序与单位正确")
    void parseRealPayload() {
        List<WeeklyBar> bars = provider.parse("000001", REAL_BODY);

        assertNotNull(bars);
        assertEquals(8, bars.size());

        WeeklyBar first = bars.getFirst();
        assertEquals(LocalDate.of(2026, 9, 11), first.tradeDate());
        assertEquals(0, new BigDecimal("11.62").compareTo(first.open()));
        assertEquals(0, new BigDecimal("11.49").compareTo(first.close()));
        assertEquals(0, new BigDecimal("11.63").compareTo(first.high()));
        assertEquals(0, new BigDecimal("11.40").compareTo(first.low()));
        assertEquals(0, new BigDecimal("4110191").compareTo(first.volume()));
        assertEquals(0, new BigDecimal("4831448655.28").compareTo(first.amount()));

        WeeklyBar last = bars.getLast();
        assertEquals(LocalDate.of(2026, 10, 30), last.tradeDate());
        assertEquals(0, new BigDecimal("11.64").compareTo(last.close()));
        assertEquals(0, new BigDecimal("11.67").compareTo(last.high()));
        assertEquals(0, new BigDecimal("11.13").compareTo(last.low()));
    }

    @Test
    @DisplayName("升序返回：首根日期早于末根，可直接喂给计算引擎")
    void barsAreAscending() {
        List<WeeklyBar> bars = provider.parse("000001", REAL_BODY);
        for (int i = 1; i < bars.size(); i++) {
            assertTrue(bars.get(i - 1).tradeDate().isBefore(bars.get(i).tradeDate()),
                    "周线应按日期升序返回");
        }
    }

    @Test
    @DisplayName("前复权负价原样保留：适配器不过滤，由计算引擎防护")
    void keepsNegativeForwardAdjustedPrice() {
        // 实测贵州茅台 2001 年周线在 fqt=1 下为负值（-312.68），适配器不得篡改真实数据
        String body = """
                {"rc":0,"data":{"code":"600519","klines":[
                  "2001-08-31,-300.10,-312.68,-280.00,-330.00,12345,67890.00,5.00"]}}
                """;
        List<WeeklyBar> bars = provider.parse("600519", body);
        assertEquals(1, bars.size());
        assertEquals(0, new BigDecimal("-312.68").compareTo(bars.getFirst().close()));
    }

    @Test
    @DisplayName("data 为 null：取数成功但无 K 线 → 空列表（非 MISSING）")
    void dataNullYieldsEmptyList() {
        List<WeeklyBar> bars = provider.parse("000001", "{\"rc\":0,\"data\":null}");
        assertNotNull(bars);
        assertTrue(bars.isEmpty());
    }

    @Test
    @DisplayName("klines 缺失或非数组 → 空列表")
    void klinesMissingYieldsEmptyList() {
        assertTrue(provider.parse("000001", "{\"rc\":0,\"data\":{\"code\":\"000001\"}}").isEmpty());
        assertTrue(provider.parse("000001", "{\"rc\":0,\"data\":{\"klines\":\"2026-10-30\"}}").isEmpty());
        assertTrue(provider.parse("000001", "{\"rc\":0,\"data\":{\"klines\":[]}}").isEmpty());
    }

    @Test
    @DisplayName("非法 JSON / 空报文 → null（MISSING，调用方保留库内旧数据）")
    void malformedJsonYieldsNull() {
        assertNull(provider.parse("000001", "<html>502 Bad Gateway</html>"));
        assertNull(provider.parse("000001", ""));
        assertNull(provider.parse("000001", "{\"rc\":0,\"data\":"));
    }

    @Test
    @DisplayName("单根异常只跳过该行：字段不足 / 日期非法 / 收盘价非法")
    void skipsBrokenRowsOnly() {
        String body = """
                {"rc":0,"data":{"klines":[
                  "2026-10-16,11.62,11.50,11.83,11.26,5646271,6524418401.40,4.92",
                  "2026-10-23,11.48,11.20",
                  "2026-13-45,11.48,11.20,11.68,11.17,6072885,6923529239.53,4.43",
                  "2026-10-30,11.15,-,11.67,11.13,8397163,9584245034.51,4.82",
                  "",
                  "2026-11-06,11.20,11.30,11.40,11.10,100,200.00,1.00"]}}
                """;
        List<WeeklyBar> bars = provider.parse("000001", body);
        assertEquals(2, bars.size());
        assertEquals(LocalDate.of(2026, 10, 16), bars.get(0).tradeDate());
        assertEquals(LocalDate.of(2026, 11, 6), bars.get(1).tradeDate());
    }

    @Test
    @DisplayName("非收盘价字段非法时保留该根：收盘价是周线指标唯一输入")
    void keepsRowWhenOptionalFieldBroken() {
        String body = """
                {"rc":0,"data":{"klines":["2026-10-30,abc,11.64,-,11.13,xyz,9584245034.51,4.82"]}}
                """;
        List<WeeklyBar> bars = provider.parse("000001", body);
        assertEquals(1, bars.size());
        WeeklyBar bar = bars.getFirst();
        assertEquals(0, new BigDecimal("11.64").compareTo(bar.close()));
        assertNull(bar.open());
        assertNull(bar.high());
        assertNull(bar.volume());
        assertEquals(0, new BigDecimal("9584245034.51").compareTo(bar.amount()));
    }
}
