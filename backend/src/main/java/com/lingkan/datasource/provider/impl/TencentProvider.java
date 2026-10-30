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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯财经行情适配器。
 *
 * <p>接口：{@code https://qt.gtimg.cn/q=sh600519,sz000001}，支持一次请求多只股票。
 * 实测（2026-10-30）：HTTP 200，单次 3 只（沪/深/北）返回正常，响应为 GBK 编码文本。
 *
 * <p>字段位置基于实测报文确定：3 现价、4 昨收、5 开盘、6 成交量(手)、30 时间、
 * 31 涨跌额、32 涨跌幅、33 最高、34 最低、35 现价/量/额、37 成交额(万)、
 * 38 换手率、43 振幅、44 流通市值(亿)、45 总市值(亿)、46 市净率。
 */
@Component
public class TencentProvider implements QuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentProvider.class);
    public static final String CODE = "tencent";

    private static final Pattern LINE_PATTERN =
            Pattern.compile("v_([a-z]{2}\\d{6})=\"([^\"]*)\";");
    private static final DateTimeFormatter DATA_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final List<String> SUPPORTED = List.of(
            "latest_price", "change_percent", "change_amount", "open_price", "high_price",
            "low_price", "pre_close", "volume", "amount", "turnover_rate", "amplitude",
            "total_market_cap", "circulating_market_cap", "pb", "stock_name", "stock_code"
    );

    private final HttpClient httpClient;

    public TencentProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    @Override
    public String getProviderName() {
        return "腾讯财经";
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
            return ProviderResult.missing(stockCode, metricCode, CODE, "腾讯行情未返回该股票");
        }
        QuoteResult quote = quotes.getFirst();
        BigDecimal value = switch (metricCode) {
            case "latest_price" -> quote.latestPrice();
            case "change_percent" -> quote.changePercent();
            case "change_amount" -> quote.changeAmount();
            case "open_price" -> quote.openPrice();
            case "high_price" -> quote.highPrice();
            case "low_price" -> quote.lowPrice();
            case "pre_close" -> quote.preClose();
            case "volume" -> quote.volume();
            case "amount" -> quote.amount();
            case "turnover_rate" -> quote.turnoverRate();
            case "total_market_cap" -> quote.totalMarketCap();
            default -> null;
        };
        if (value == null) {
            return ProviderResult.missing(stockCode, metricCode, CODE, "该指标不适用于腾讯行情接口");
        }
        return ProviderResult.ok(stockCode, metricCode, CODE, value.toPlainString(), value, unitOf(metricCode),
                quote.dataTime());
    }

    private String unitOf(String metricCode) {
        return switch (metricCode) {
            case "change_percent", "turnover_rate", "amplitude" -> "%";
            case "volume" -> "手";
            case "amount", "total_market_cap", "circulating_market_cap" -> "元";
            case "pb" -> "倍";
            default -> "元";
        };
    }

    @Override
    public List<QuoteResult> fetchQuotes(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return List.of();
        }
        List<String> symbols = stockCodes.stream().map(TencentProvider::toSymbol).toList();
        String url = "https://qt.gtimg.cn/q=" + String.join(",", symbols);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0 LingKan/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("腾讯行情返回状态码 {}", response.statusCode());
                return List.of();
            }
            // 腾讯返回 GBK 编码
            String body = new String(response.body(), java.nio.charset.Charset.forName("GBK"));
            return parse(body);
        } catch (Exception ex) {
            log.warn("腾讯行情请求失败: {}", ex.getMessage());
            return List.of();
        }
    }

    static List<QuoteResult> parse(String body) {
        List<QuoteResult> results = new ArrayList<>();
        Matcher matcher = LINE_PATTERN.matcher(body);
        while (matcher.find()) {
            String symbol = matcher.group(1);
            String[] f = matcher.group(2).split("~", -1);
            if (f.length < 46) {
                continue;
            }
            String code = symbol.substring(2);
            results.add(new QuoteResult(
                    code,
                    f[1],
                    CODE,
                    DecimalUtils.of(f[3]),
                    DecimalUtils.of(f[32]),
                    DecimalUtils.of(f[31]),
                    DecimalUtils.of(f[5]),
                    DecimalUtils.of(f[33]),
                    DecimalUtils.of(f[34]),
                    DecimalUtils.of(f[4]),
                    DecimalUtils.of(f[6]),
                    // 成交额单位为万元，统一换算为元
                    scale(DecimalUtils.of(f[37]), BigDecimal.valueOf(10000)),
                    DecimalUtils.of(f[38]),
                    // 总市值单位为亿元，统一换算为元
                    scale(DecimalUtils.of(f[45]), BigDecimal.valueOf(100000000)),
                    parseTime(f[30]),
                    LocalDateTime.now()
            ));
        }
        return results;
    }

    private static BigDecimal scale(BigDecimal value, BigDecimal factor) {
        return value == null ? null : value.multiply(factor);
    }

    private static LocalDateTime parseTime(String raw) {
        try {
            return LocalDateTime.parse(raw, DATA_TIME_FORMAT);
        } catch (Exception ex) {
            return LocalDateTime.now();
        }
    }

    /** 交易所前缀：SH -> sh，SZ -> sz，BJ -> bj */
    public static String toSymbol(String stockCode) {
        String code = stockCode.contains(".") ? stockCode.substring(0, stockCode.indexOf('.')) : stockCode;
        if (code.startsWith("6") || code.startsWith("9")) {
            return "sh" + code;
        }
        if (code.startsWith("4") || code.startsWith("8")) {
            return "bj" + code;
        }
        return "sz" + code;
    }

    @Override
    public ProviderHealth checkHealth() {
        long start = System.currentTimeMillis();
        List<QuoteResult> quotes = fetchQuotes(List.of("600519"));
        long cost = System.currentTimeMillis() - start;
        if (quotes.isEmpty()) {
            return ProviderHealth.down(CODE, cost, "腾讯行情无返回");
        }
        QuoteResult q = quotes.getFirst();
        return ProviderHealth.up(CODE, cost,
                "返回 " + q.stockName() + " 最新价 " + (q.latestPrice() == null ? "--" : q.latestPrice()));
    }

    public Map<String, String> describe() {
        return Map.of(
                "provider", CODE,
                "endpoint", "https://qt.gtimg.cn/q={symbols}",
                "batch", "true",
                "encoding", "GBK"
        );
    }
}
