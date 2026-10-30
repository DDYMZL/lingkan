package com.lingkan.datasource.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.common.util.DecimalUtils;
import com.lingkan.datasource.provider.ProviderHealth;
import com.lingkan.datasource.provider.ProviderResult;
import com.lingkan.datasource.provider.StockDataProvider;
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
import java.util.List;
import java.util.Map;

/**
 * 东方财富适配器（单只股票快照）。
 *
 * <p>接口：{@code https://push2.eastmoney.com/api/qt/stock/get?secid=1.600519&fields=...}
 * 实测（2026-10-30）：HTTP 200，贵州茅台返回最新价 1392.05、总市值 1.74 万亿等字段。
 *
 * <p>价格类字段单位为"分"（需除以 100），成交量单位为"手"，成交额与市值单位为"元"。
 * 实测已核对：f43/100 = 1392.05 与腾讯、新浪一致。
 */
@Component
public class EastMoneyProvider implements StockDataProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyProvider.class);
    public static final String CODE = "eastmoney";

    private static final String ENDPOINT =
            "https://push2.eastmoney.com/api/qt/stock/get?secid=%s&fields="
                    + "f43,f44,f45,f46,f47,f48,f50,f57,f58,f60,f116,f117,f167,f168,f169,f170";

    /** 需要除以 100 的字段 */
    private static final List<String> SCALED_METRICS = List.of(
            "latest_price", "high_price", "low_price", "open_price", "pre_close",
            "change_amount", "change_percent", "turnover_rate", "volume_ratio", "pb"
    );

    private static final Map<String, String> FIELD_MAPPING = Map.ofEntries(
            Map.entry("latest_price", "f43"),
            Map.entry("high_price", "f44"),
            Map.entry("low_price", "f45"),
            Map.entry("open_price", "f46"),
            Map.entry("volume", "f47"),
            Map.entry("amount", "f48"),
            Map.entry("volume_ratio", "f50"),
            Map.entry("pre_close", "f60"),
            Map.entry("total_market_cap", "f116"),
            Map.entry("circulating_market_cap", "f117"),
            Map.entry("pb", "f167"),
            Map.entry("turnover_rate", "f168"),
            Map.entry("change_amount", "f169"),
            Map.entry("change_percent", "f170")
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EastMoneyProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient,
                             ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProviderCode() {
        return CODE;
    }

    @Override
    public String getProviderName() {
        return "东方财富";
    }

    @Override
    public boolean supports(String metricCode) {
        return FIELD_MAPPING.containsKey(metricCode);
    }

    @Override
    public List<String> supportedMetrics() {
        return List.copyOf(FIELD_MAPPING.keySet());
    }

    @Override
    public ProviderResult fetch(String stockCode, String metricCode) {
        String field = FIELD_MAPPING.get(metricCode);
        if (field == null) {
            return ProviderResult.error(stockCode, metricCode, CODE, "东方财富适配器不支持该指标");
        }
        JsonNode data = requestSnapshot(stockCode);
        if (data == null) {
            return ProviderResult.error(stockCode, metricCode, CODE, "东方财富接口无有效返回");
        }
        JsonNode node = data.get(field);
        if (node == null || node.isNull()) {
            return ProviderResult.missing(stockCode, metricCode, CODE, "字段 " + field + " 为空");
        }
        BigDecimal value = DecimalUtils.of(node.asDouble());
        if (value != null && SCALED_METRICS.contains(metricCode)) {
            value = value.divide(BigDecimal.valueOf(100), DecimalUtils.MC);
        }
        return ProviderResult.ok(stockCode, metricCode, CODE, node.asText(), value,
                unitOf(metricCode), LocalDateTime.now());
    }

    private JsonNode requestSnapshot(String stockCode) {
        String url = String.format(ENDPOINT, secid(stockCode));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "Mozilla/5.0 LingKan/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("东方财富返回状态码 {}", response.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.get("data");
            return data == null || data.isNull() ? null : data;
        } catch (Exception ex) {
            log.warn("东方财富请求失败: {}", ex.getMessage());
            return null;
        }
    }

    private String unitOf(String metricCode) {
        return switch (metricCode) {
            case "change_percent", "turnover_rate" -> "%";
            case "volume" -> "手";
            case "volume_ratio", "pb" -> "倍";
            default -> "元";
        };
    }

    /**
     * 转东方财富 secid：沪市 1.xxxxxx，深市/北交所 0.xxxxxx。
     * 北交所 secid 规则尚未单独验证，接入时需以具体股票复核。
     */
    static String secid(String stockCode) {
        String code = stockCode.contains(".")
                ? stockCode.substring(0, stockCode.indexOf('.'))
                : stockCode;
        if (code.startsWith("6") || code.startsWith("9")) {
            return "1." + code;
        }
        return "0." + code;
    }

    @Override
    public ProviderHealth checkHealth() {
        long start = System.currentTimeMillis();
        JsonNode data = requestSnapshot("600519");
        long cost = System.currentTimeMillis() - start;
        if (data == null) {
            return ProviderHealth.down(CODE, cost, "东方财富快照接口无返回");
        }
        return ProviderHealth.up(CODE, cost,
                "返回 " + data.path("f58").asText("--") + " 现价字段 " + data.path("f43").asText("--"));
    }
}
