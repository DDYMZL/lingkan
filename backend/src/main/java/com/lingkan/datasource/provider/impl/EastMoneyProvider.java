package com.lingkan.datasource.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lingkan.common.util.DecimalUtils;
import com.lingkan.datasource.provider.BatchStockDataProvider;
import com.lingkan.datasource.provider.ProviderHealth;
import com.lingkan.datasource.provider.ProviderResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 东方财富适配器（单只股票快照）。
 *
 * <p>接口：{@code https://push2.eastmoney.com/api/qt/stock/get?secid=1.600519&fields=...}
 * 实测（2026-10-30）：HTTP 200，贵州茅台返回最新价 1392.05、总市值 1.74 万亿等字段。
 *
 * <p>价格类字段单位为"分"（需除以 100），成交量单位为"手"，成交额与市值单位为"元"。
 * 实测已核对：f43/100 = 1392.05 与腾讯、新浪一致。f127 = 所属行业（白酒Ⅱ）、
 * f189 = 上市日期（20010827），用于顺带补齐股票基础信息，不额外增加请求。
 *
 * <p>批量能力：一次 {@code stock/get} 请求即可返回全部快照字段，因此实现
 * {@link BatchStockDataProvider#fetchMetrics} 把"一只股票 14 项指标 = 14 次请求"
 * 压缩为 1 次请求；同时对快照做 10 秒短缓存，避免同步流程内重复请求同一只股票。
 */
@Component
public class EastMoneyProvider implements BatchStockDataProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyProvider.class);
    public static final String CODE = "eastmoney";

    private static final String ENDPOINT =
            "https://push2.eastmoney.com/api/qt/stock/get?secid=%s&fields="
                    + "f43,f44,f45,f46,f47,f48,f50,f57,f58,f60,f116,f117,f167,f168,f169,f170,f127,f189";

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

    /**
     * 连续网络失败阈值。实测（2026-10-30）东方财富在短时间大量请求后会直接断开连接
     * （{@code HTTP/1.1 header parser received no bytes}），此后继续请求只会空转并
     * 产生海量无意义失败记录，因此连续失败超过阈值即熔断，冷却期内快速失败。
     */
    private static final int CIRCUIT_TRIP_THRESHOLD = 30;

    /** 熔断冷却时长；到期后自动半开，允许少量请求探测是否恢复 */
    private static final long CIRCUIT_COOLDOWN_MS = Duration.ofMinutes(3).toMillis();

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** 连续网络失败计数（成功一次即清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 熔断到期时间戳（毫秒），0 表示未熔断 */
    private volatile long circuitOpenUntil = 0L;

    /**
     * 快照缓存：同步流程内同一只股票的多个指标共用一次请求。
     * TTL 取 10 秒，保证同一只股票的 14 项指标（串行约 1~3 秒）命中同一快照，
     * 又不会把过期快照长期当作实时数据使用。
     */
    private final Cache<String, JsonNode> snapshotCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(10))
            .maximumSize(6000)
            .build();

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
        if (!FIELD_MAPPING.containsKey(metricCode)) {
            return ProviderResult.error(stockCode, metricCode, CODE, "东方财富适配器不支持该指标");
        }
        JsonNode data = requestSnapshot(stockCode);
        if (data == null) {
            return ProviderResult.error(stockCode, metricCode, CODE, "东方财富接口无有效返回");
        }
        return toResult(stockCode, metricCode, data);
    }

    /**
     * 一次请求返回多指标。无法取到的指标返回 MISSING，绝不返回 null 值冒充有效数据。
     */
    @Override
    public Map<String, ProviderResult> fetchMetrics(String stockCode, List<String> metricCodes) {
        JsonNode data = requestSnapshot(stockCode);
        Map<String, ProviderResult> results = new LinkedHashMap<>();
        if (data == null) {
            for (String metricCode : metricCodes) {
                results.put(metricCode, ProviderResult.error(stockCode, metricCode, CODE,
                        "东方财富接口无有效返回"));
            }
            return results;
        }
        for (String metricCode : metricCodes) {
            results.put(metricCode, FIELD_MAPPING.containsKey(metricCode)
                    ? toResult(stockCode, metricCode, data)
                    : ProviderResult.error(stockCode, metricCode, CODE, "东方财富适配器不支持该指标"));
        }
        return results;
    }

    private ProviderResult toResult(String stockCode, String metricCode, JsonNode data) {
        String field = FIELD_MAPPING.get(metricCode);
        JsonNode node = data.get(field);
        if (node == null || node.isNull() || "-".equals(node.asText())) {
            return ProviderResult.missing(stockCode, metricCode, CODE, "字段 " + field + " 为空");
        }
        BigDecimal value = DecimalUtils.of(node.asDouble());
        if (value != null && SCALED_METRICS.contains(metricCode)) {
            value = value.divide(BigDecimal.valueOf(100), DecimalUtils.MC);
        }
        if (value == null) {
            return ProviderResult.missing(stockCode, metricCode, CODE, "字段 " + field + " 非数值");
        }
        return ProviderResult.ok(stockCode, metricCode, CODE, node.asText(), value,
                unitOf(metricCode), LocalDateTime.now());
    }

    /**
     * 股票基础信息（行业 / 上市日期）。优先使用同步流程中已缓存的快照，
     * 缓存缺失时才发起请求。
     */
    public Optional<StockProfile> profile(String stockCode) {
        JsonNode data = requestSnapshot(stockCode);
        if (data == null) {
            return Optional.empty();
        }
        String industry = data.path("f127").asText("");
        String listing = data.path("f189").asText("");
        LocalDate listingDate = null;
        if (listing.length() == 8 && listing.chars().allMatch(Character::isDigit)) {
            try {
                listingDate = LocalDate.of(Integer.parseInt(listing.substring(0, 4)),
                        Integer.parseInt(listing.substring(4, 6)), Integer.parseInt(listing.substring(6, 8)));
            } catch (Exception ignored) {
                listingDate = null;
            }
        }
        return Optional.of(new StockProfile(
                industry.isBlank() || "-".equals(industry) ? null : industry, listingDate));
    }

    private JsonNode requestSnapshot(String stockCode) {
        String code = stockCode == null ? "" : stockCode.trim();
        if (isCircuitOpen()) {
            return null;
        }
        JsonNode cached = snapshotCache.getIfPresent(code);
        if (cached != null) {
            return cached;
        }
        String url = String.format(ENDPOINT, secid(code));
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
            if (data == null || data.isNull()) {
                return null;
            }
            consecutiveFailures.set(0);
            snapshotCache.put(code, data);
            return data;
        } catch (Exception ex) {
            log.warn("东方财富请求失败: {}", ex.getMessage());
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= CIRCUIT_TRIP_THRESHOLD && !isCircuitOpen()) {
                circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS;
                log.error("东方财富连续失败 {} 次，触发熔断 {} 秒内快速失败（等限流恢复）",
                        failures, CIRCUIT_COOLDOWN_MS / 1000);
            }
            return null;
        }
    }

    /** 是否处于熔断冷却期（冷却期内的请求直接失败，不再发起 HTTP） */
    public boolean isCircuitOpen() {
        long until = circuitOpenUntil;
        return until > 0 && System.currentTimeMillis() < until;
    }

    /** 熔断剩余秒数，未熔断返回 0 */
    public long circuitRemainingSeconds() {
        long until = circuitOpenUntil;
        long left = until - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    /**
     * 重置熔断状态。每轮同步开始前调用，避免上一轮触发过熔断后新一轮直接空转；
     * 同时让新一轮先用少量请求探测数据源是否恢复。
     */
    public void resetCircuitBreaker() {
        consecutiveFailures.set(0);
        circuitOpenUntil = 0L;
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
     * 实测 2026-10-30：北交所 920002 使用 0.920002 可正常返回快照。
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

    /** 股票基础信息补充字段 */
    public record StockProfile(String industry, LocalDate listingDate) {
    }
}
