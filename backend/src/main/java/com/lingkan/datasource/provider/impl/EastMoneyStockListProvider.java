package com.lingkan.datasource.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.datasource.provider.StockListProvider;
import com.lingkan.datasource.provider.StockListUnavailableException;
import com.lingkan.sync.dto.StockBasicDto;
import com.lingkan.sync.support.StockCodeRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 东方财富全市场股票清单适配器。
 *
 * <p>接口：{@code https://push2.eastmoney.com/api/qt/clist/get}，2026-10-30 实测结论：
 * <ol>
 *   <li>分页参数 {@code pn} 从 1 开始；{@code pz} 请求 500 时服务端只返回 100 条，
 *       即单页上限 100，必须按 100 分页拉取；</li>
 *   <li>全市场（沪深主板 + 创业板 + 科创板 + 北交所）{@code total} 约 5900 条，
 *       其中包含已退市证券（最新价与行业字段均为 "-"），需剔除；</li>
 *   <li>{@code f13} 只区分 1=沪、0=深/北，北交所无法与深市区分，交易所判定以代码前缀为准；</li>
 *   <li>短时间连续高频请求会被上游拒绝（连接被直接关闭），必须控制分页请求间隔并重试。</li>
 * </ol>
 */
@Component
public class EastMoneyStockListProvider implements StockListProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyStockListProvider.class);

    public static final String CODE = "eastmoney";

    /** 沪深主板 + 创业板 + 科创板 + 北交所 */
    private static final String FS_ALL = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048";
    private static final String FS_SH = "m:1+t:2,m:1+t:23";
    private static final String FS_SZ = "m:0+t:6,m:0+t:80";
    private static final String FS_BJ = "m:0+t:81+s:2048";

    /** f12 代码、f13 市场、f14 名称、f100 行业、f2 最新价、f26 上市日期 */
    private static final String FIELDS = "f12,f13,f14,f100,f2,f26";

    private static final String PATH =
            "/api/qt/clist/get?pn=%d&pz=%d&po=0&np=1&fltt=2&invt=2&fid=f12&fs=%s&fields=" + FIELDS;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** 单页请求条数：服务端实际上限 100，请求更大值会被截断 */
    @Value("${lingkan.stock-sync.stock-list.page-size:100}")
    private int pageSize;

    /** 最大分页数保护，避免异常响应导致死循环 */
    @Value("${lingkan.stock-sync.stock-list.max-pages:120}")
    private int maxPages;

    /** 分页请求间隔，降低被上游限流的概率 */
    @Value("${lingkan.stock-sync.stock-list.request-interval-ms:300}")
    private int requestIntervalMs;

    @Value("${lingkan.stock-sync.stock-list.max-retries:3}")
    private int maxRetries;

    @Value("${lingkan.stock-sync.stock-list.retry-interval-ms:1500}")
    private int retryIntervalMs;

    @Value("${lingkan.stock-sync.stock-list.hosts:push2.eastmoney.com,82.push2.eastmoney.com,push2delay.eastmoney.com}")
    private String hosts;

    public EastMoneyStockListProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient,
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
    public List<StockBasicDto> fetchAllStocks() {
        return fetch(FS_ALL, "全市场");
    }

    @Override
    public List<StockBasicDto> fetchStocksByExchange(String exchange) {
        String fs = switch (exchange == null ? "" : exchange.toUpperCase()) {
            case "SH" -> FS_SH;
            case "SZ" -> FS_SZ;
            case "BJ" -> FS_BJ;
            default -> throw new IllegalArgumentException("不支持的交易所: " + exchange);
        };
        return fetch(fs, exchange);
    }

    /**
     * 单只股票基础信息补录：走 stock/get 快照接口（实测 clist 高频请求会被限流，
     * 而 stock/get 在同等频率下稳定；f127=行业、f189=上市日期）。
     */
    @Override
    public StockBasicDto fetchStock(String stockCode) {
        String code = StockCodeRules.normalize(stockCode);
        if (code == null || code.isBlank()) {
            return null;
        }
        String secid = (StockCodeRules.SH.equals(StockCodeRules.exchangeOf(code)) ? "1." : "0.") + code;
        String url = "https://" + firstHost() + "/api/qt/stock/get?secid=" + secid
                + "&fields=f57,f58,f127,f189";
        JsonNode data = requestJson(url, "单股基础信息 " + code);
        JsonNode node = data == null ? null : data.get("data");
        if (node == null || node.isNull() || node.path("f58").asText("").isBlank()) {
            return null;
        }
        String name = node.path("f58").asText();
        String industry = node.path("f127").asText(null);
        LocalDate listingDate = parseDate(node.path("f189"));
        return StockCodeRules.build(code, name, industry, StockBasicDto.STATUS_LISTED, listingDate);
    }

    private List<StockBasicDto> fetch(String fs, String scope) {
        Map<String, StockBasicDto> collected = new LinkedHashMap<>();
        int rawRows = 0;
        long total = -1;
        int skippedNonTrading = 0;
        int page = 1;
        while (page <= maxPages) {
            JsonNode data = requestPage(page, fs, scope);
            if (data == null) {
                throw new StockListUnavailableException(
                        "东方财富股票清单第 " + page + " 页获取失败（" + scope + "）");
            }
            long pageTotal = data.path("total").asLong(-1);
            if (pageTotal >= 0) {
                total = pageTotal;
            }
            List<JsonNode> rows = rowsOf(data.get("diff"));
            if (rows.isEmpty()) {
                break;
            }
            rawRows += rows.size();
            for (JsonNode row : rows) {
                StockBasicDto dto = parseRow(row);
                if (dto == null) {
                    skippedNonTrading++;
                    continue;
                }
                collected.putIfAbsent(dto.exchange() + ":" + dto.stockCode(), dto);
            }
            log.info("东方财富股票清单 {} 第 {} 页：本页 {} 条，累计有效 {} 条 / 接口总数 {}",
                    scope, page, rows.size(), collected.size(), total);
            if (rows.size() < pageSize || (total > 0 && (long) page * pageSize >= total)) {
                break;
            }
            page++;
            sleep(requestIntervalMs);
        }

        if (total <= 0) {
            throw new StockListUnavailableException("东方财富股票清单未返回有效总数（" + scope + "）");
        }
        if (rawRows < total) {
            throw new StockListUnavailableException(String.format(
                    "东方财富股票清单分页不完整：接口总数 %d，实际获取 %d（%s）", total, rawRows, scope));
        }
        if (collected.isEmpty()) {
            throw new StockListUnavailableException("东方财富股票清单为空（" + scope + "）");
        }
        log.info("东方财富股票清单 {} 完成：接口总数 {}，有效 {} 条，剔除无行情/无行业证券 {} 条",
                scope, total, collected.size(), skippedNonTrading);
        return new ArrayList<>(collected.values());
    }

    private JsonNode requestPage(int page, String fs, String scope) {
        String path = String.format(PATH, page, pageSize, fs);
        List<String> candidates = hostList();
        for (int attempt = 1; attempt <= Math.max(1, maxRetries); attempt++) {
            for (String host : candidates) {
                JsonNode root = tryRequest("https://" + host + path, scope + " 第" + page + "页");
                if (root != null) {
                    JsonNode data = root.get("data");
                    if (data != null && !data.isNull() && data.has("diff")) {
                        return data;
                    }
                }
            }
            sleep((long) retryIntervalMs * attempt);
        }
        return null;
    }

    private JsonNode requestJson(String url, String scope) {
        for (int attempt = 1; attempt <= Math.max(1, maxRetries); attempt++) {
            JsonNode root = tryRequest(url, scope);
            if (root != null) {
                return root;
            }
            sleep((long) retryIntervalMs * attempt);
        }
        return null;
    }

    private JsonNode tryRequest(String url, String scope) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) LingKan/1.0")
                    .header("Referer", "https://quote.eastmoney.com/")
                    .header("Accept", "*/*")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("东方财富 {} 返回状态码 {}", scope, response.statusCode());
                return null;
            }
            if (response.body() == null || response.body().isBlank()) {
                log.warn("东方财富 {} 返回空响应", scope);
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            log.warn("东方财富 {} 请求失败: {}", scope, ex.getMessage());
            return null;
        }
    }

    /**
     * 单行解析。返回 null 表示非正常交易的 A 股（已退市或长期无行情），由调用方剔除并计数。
     */
    private StockBasicDto parseRow(JsonNode row) {
        String code = row.path("f12").asText("");
        String name = row.path("f14").asText("");
        if (!StockCodeRules.isAShareCode(code) || name.isBlank()) {
            return null;
        }
        String industry = row.path("f100").asText("");
        String price = row.path("f2").asText("");
        boolean hasIndustry = industry != null && !industry.isBlank() && !"-".equals(industry);
        boolean hasPrice = price != null && !price.isBlank() && !"-".equals(price);
        if (!hasIndustry && !hasPrice) {
            // 实测：退市证券最新价与行业同时为 "-"，此类证券不进入 stocks
            return null;
        }
        LocalDate listingDate = parseDate(row.path("f26"));
        return StockCodeRules.build(code, name, hasIndustry ? industry : null,
                StockBasicDto.STATUS_LISTED, listingDate);
    }

    /** diff 既可能是数组，也可能是以下标为 key 的对象 */
    private List<JsonNode> rowsOf(JsonNode diff) {
        List<JsonNode> rows = new ArrayList<>();
        if (diff == null || diff.isNull()) {
            return rows;
        }
        if (diff.isArray()) {
            diff.forEach(rows::add);
        } else if (diff.isObject()) {
            diff.elements().forEachRemaining(rows::add);
        }
        return rows;
    }

    private LocalDate parseDate(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String raw = node.asText("").trim();
        if (raw.length() != 8 || !raw.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(raw.substring(0, 4)),
                    Integer.parseInt(raw.substring(4, 6)), Integer.parseInt(raw.substring(6, 8)));
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> hostList() {
        LinkedHashSet<String> list = new LinkedHashSet<>();
        for (String host : hosts.split(",")) {
            if (!host.isBlank()) {
                list.add(host.trim());
            }
        }
        if (list.isEmpty()) {
            list.add("push2.eastmoney.com");
        }
        return new ArrayList<>(list);
    }

    private String firstHost() {
        return hostList().getFirst();
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
