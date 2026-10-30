package com.lingkan.datasource.provider.impl;

import com.lingkan.common.util.DecimalUtils;
import com.lingkan.datasource.provider.ProviderHealth;
import com.lingkan.datasource.provider.ProviderResult;
import com.lingkan.datasource.provider.QuoteProvider;
import com.lingkan.datasource.provider.QuoteResult;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新浪财经行情适配器（定位：行情辅助验证）。
 *
 * <p>接口：{@code https://hq.sinajs.cn/list=sh600519,sz000001}
 * 必须带 Referer: https://finance.sina.com.cn，否则返回 403。
 * 实测（2026-10-30）：HTTP 200，返回 CSV 文本，字段含义如下（下标）：
 * 0 名称、1 今开、2 昨收、3 现价、4 最高、5 最低、6 买一、7 卖一、
 * 8 成交量(股)、9 成交额(元)、30 日期、31 时间。
 */
@Component
public class SinaProvider implements QuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(SinaProvider.class);
    public static final String CODE = "sina";

    private static final Pattern LINE_PATTERN =
            Pattern.compile("var hq_str_([a-z]{2}\\d{6})=\"([^\"]*)\";");

    private static final List<String> SUPPORTED = List.of(
            "latest_price", "open_price", "high_price", "low_price", "pre_close",
            "volume", "amount", "stock_name"
    );

    private final HttpClient httpClient;

    public SinaProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    @Override
    public String getProviderName() {
        return "新浪财经";
    }

    @Override
    public boolean supports(String metricCode) {
        return SUPPORTED.contains(metricCode);
    }

    @Override
    public List<String> supportedMetrics() {
        return SUPPORTED;
    }

    @Override
    public ProviderResult fetch(String stockCode, String metricCode) {
        List<QuoteResult> quotes = fetchQuotes(List.of(stockCode));
        if (quotes.isEmpty()) {
            return ProviderResult.missing(stockCode, metricCode, CODE, "新浪行情未返回该股票");
        }
        QuoteResult quote = quotes.getFirst();
        BigDecimal value = switch (metricCode) {
            case "latest_price" -> quote.latestPrice();
            case "open_price" -> quote.openPrice();
            case "high_price" -> quote.highPrice();
            case "low_price" -> quote.lowPrice();
            case "pre_close" -> quote.preClose();
            case "volume" -> quote.volume();
            case "amount" -> quote.amount();
            default -> null;
        };
        if (value == null) {
            return ProviderResult.missing(stockCode, metricCode, CODE, "该指标不适用于新浪行情接口");
        }
        return ProviderResult.ok(stockCode, metricCode, CODE, value.toPlainString(), value,
                "volume".equals(metricCode) ? "手" : "amount".equals(metricCode) ? "元" : "元",
                quote.dataTime());
    }

    @Override
    public List<QuoteResult> fetchQuotes(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        String url = "https://hq.sinajs.cn/list="
                + String.join(",", stockCodes.stream().map(TencentProvider::toSymbol).toList());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0 LingKan/1.0")
                    .header("Referer", "https://finance.sina.com.cn")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("新浪行情返回状态码 {}", response.statusCode());
                return List.of();
            }
            String body = new String(response.body(), java.nio.charset.Charset.forName("GBK"));
            return parse(body);
        } catch (Exception ex) {
            log.warn("新浪行情请求失败: {}", ex.getMessage());
            return List.of();
        }
    }

    static List<QuoteResult> parse(String body) {
        List<QuoteResult> results = new ArrayList<>();
        Matcher matcher = LINE_PATTERN.matcher(body);
        while (matcher.find()) {
            String symbol = matcher.group(1);
            String[] f = matcher.group(2).split(",", -1);
            if (f.length < 32) {
                continue;
            }
            BigDecimal volumeShares = DecimalUtils.of(f[8]);
            // 新浪成交量单位为股，统一换算为手
            BigDecimal volumeLots = volumeShares == null
                    ? null
                    : volumeShares.divide(BigDecimal.valueOf(100), DecimalUtils.MC);
            results.add(new QuoteResult(
                    symbol.substring(2),
                    f[0],
                    CODE,
                    DecimalUtils.of(f[3]),
                    null,
                    null,
                    DecimalUtils.of(f[1]),
                    DecimalUtils.of(f[4]),
                    DecimalUtils.of(f[5]),
                    DecimalUtils.of(f[2]),
                    volumeLots,
                    DecimalUtils.of(f[9]),
                    null,
                    null,
                    parseTime(f[30], f[31]),
                    LocalDateTime.now()
            ));
        }
        return results;
    }

    private static LocalDateTime parseTime(String date, String time) {
        try {
            return LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time));
        } catch (Exception ex) {
            return LocalDateTime.now();
        }
    }

    @Override
    public ProviderHealth checkHealth() {
        long start = System.currentTimeMillis();
        List<QuoteResult> quotes = fetchQuotes(List.of("600519"));
        long cost = System.currentTimeMillis() - start;
        if (quotes.isEmpty()) {
            return ProviderHealth.down(CODE, cost, "新浪行情无返回（可能缺少 Referer 或被限流）");
        }
        return ProviderHealth.up(CODE, cost, "返回 " + quotes.getFirst().stockName() + " 行情");
    }
}
