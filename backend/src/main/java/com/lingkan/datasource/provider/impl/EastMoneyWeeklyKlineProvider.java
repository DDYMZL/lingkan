package com.lingkan.datasource.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.datasource.provider.WeeklyKlineProvider;
import com.lingkan.datasource.provider.dto.WeeklyBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富周 K 线适配器（前复权）。
 *
 * <p>接口（2026-10-31 真实请求验证，非推测）：
 * <pre>
 * https://push2his.eastmoney.com/api/qt/stock/kline/get
 *   ?secid={1|0}.{code}&amp;fields1=f1,f2,f3,f4,f5,f6
 *   &amp;fields2=f51,f52,f53,f54,f55,f56,f57,f58
 *   &amp;klt=102&amp;fqt=1&amp;end=20500101&amp;lmt={limit}
 * </pre>
 *
 * <p>实测样本（平安银行 {@code secid=0.000001}，lmt=8）：{@code rc=0}，
 * {@code data.dktotal=8507}，{@code data.klines} 为字符串数组，逗号分隔字段序固定为
 * {@code 日期,开盘,收盘,最高,最低,成交量(手),成交额(元),振幅(%)}，例如
 * {@code "2026-10-30,11.15,11.64,11.67,11.13,8397163,9584245034.51,4.82"}；
 * 日期为该周最后一个交易日，数组按日期升序。
 *
 * <p><b>前复权负价陷阱</b>：{@code fqt=1} 对累计分红极大的老股会算出负价格
 * （实测贵州茅台 2001 年周线为 −312.68）。适配器<b>不做过滤</b>，原样返回真实数据（铁律 1：不编造、不篡改），
 * 由 {@code WeeklyIndicatorCalculator} 在计算窗口内统一防护（任一收盘价 ≤ 0 → 指标不输出）。
 *
 * <p>返回语义：失败（非 200 / 网络 / 解析异常）返回 null（MISSING），成功但无 K 线返回空列表。
 * 限流由调用方（{@code WeeklyKlineSyncService}）控制，适配器自身不 sleep，便于单测。
 */
@Component
public class EastMoneyWeeklyKlineProvider implements WeeklyKlineProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyWeeklyKlineProvider.class);
    public static final String CODE = "eastmoney";

    /** 复权方式：1 = 前复权（QFQ）。看板周线五指标固定用前复权，与现价同口径可比。 */
    private static final int FQT_QFQ = 1;
    /** K 线类型：102 = 周线。 */
    private static final int KLT_WEEKLY = 102;
    /** limit 非法时的兜底根数（≥ 120 周均线所需 + 余量）。 */
    private static final int DEFAULT_LIMIT = 130;

    private static final String ENDPOINT =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=%s"
                    + "&fields1=f1,f2,f3,f4,f5,f6"
                    + "&fields2=f51,f52,f53,f54,f55,f56,f57,f58"
                    + "&klt=" + KLT_WEEKLY + "&fqt=" + FQT_QFQ
                    + "&end=20500101&lmt=%d";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EastMoneyWeeklyKlineProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient,
                                        ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<WeeklyBar> fetchWeeklyKline(String stockCode, int limit) {
        if (stockCode == null || stockCode.isBlank()) {
            return null;
        }
        String url = String.format(ENDPOINT, EastMoneyProvider.secid(stockCode),
                limit > 0 ? limit : DEFAULT_LIMIT);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 LingKan/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("东财周K接口返回状态码 {} ({})", response.statusCode(), stockCode);
                return null;
            }
            return parse(stockCode, response.body());
        } catch (Exception ex) {
            log.warn("东财周K接口请求失败 {}: {}", stockCode, ex.getMessage());
            return null;
        }
    }

    /**
     * 解析报文。包内可见以便用固定真实报文样本做单元测试（铁律 11）。
     *
     * <p>异常语义：JSON 非法 → null（MISSING，与请求失败同义，调用方保留库内旧数据）；
     * {@code data} 缺失/为 null 或 {@code klines} 为空 → 空列表（取数成功但无 K 线）；
     * 单根字段不足、日期非法或收盘价非法 → 跳过该根，不影响其余。
     */
    List<WeeklyBar> parse(String stockCode, String body) {
        if (body == null || body.isBlank()) {
            log.warn("东财周K报文为空 {}", stockCode);
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            log.warn("东财周K报文解析失败 {}: {}", stockCode, ex.getMessage());
            return null;
        }
        if (root == null || root.isMissingNode()) {
            return null;
        }
        JsonNode data = root.get("data");
        List<WeeklyBar> bars = new ArrayList<>();
        if (data == null || data.isNull()) {
            return bars;
        }
        JsonNode klines = data.get("klines");
        if (klines == null || !klines.isArray()) {
            return bars;
        }
        for (JsonNode line : klines) {
            WeeklyBar bar = toBar(line.asText());
            if (bar != null) {
                bars.add(bar);
            }
        }
        return bars;
    }

    /** 单根 K 线解析；字段序 日期,开盘,收盘,最高,最低,成交量,成交额,振幅（振幅不落库）。 */
    private static WeeklyBar toBar(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(",");
        if (parts.length < 7) {
            return null;
        }
        LocalDate tradeDate;
        try {
            tradeDate = LocalDate.parse(parts[0].trim());
        } catch (Exception ex) {
            return null;
        }
        BigDecimal close = decimal(parts[2]);
        if (close == null) {
            // 收盘价是周线五指标的唯一输入，缺失则该根无意义
            return null;
        }
        return new WeeklyBar(tradeDate, decimal(parts[1]), close, decimal(parts[3]),
                decimal(parts[4]), decimal(parts[5]), decimal(parts[6]));
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || "-".equals(value)) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
