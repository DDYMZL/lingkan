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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新浪财经全市场 A 股清单适配器（股票清单的备用数据源）。
 *
 * <p>用途：东方财富 clist 接口被限流或不可用时，保证股票清单初始化仍可完成。
 * 实测 2026-10-30：{@code Market_Center.getHQNodeData?node=hs_a} 每页 100 条，
 * 共 56 页 5576 条（含北交所 bj 前缀代码），必须带 Referer 才返回 200。
 *
 * <p>已知局限：该接口不返回行业字段，行业留空（由后续东方财富指标同步顺带补齐）。
 */
@Component
public class SinaStockListProvider implements StockListProvider {

    private static final Logger log = LoggerFactory.getLogger(SinaStockListProvider.class);

    public static final String CODE = "sina";

    private static final String URL_TEMPLATE =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                    + "Market_Center.getHQNodeData?page=%d&num=%d&sort=symbol&asc=1&node=%s";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${lingkan.stock-sync.stock-list.page-size:100}")
    private int pageSize;

    @Value("${lingkan.stock-sync.stock-list.max-pages:120}")
    private int maxPages;

    @Value("${lingkan.stock-sync.stock-list.request-interval-ms:300}")
    private int requestIntervalMs;

    @Value("${lingkan.stock-sync.stock-list.max-retries:3}")
    private int maxRetries;

    @Value("${lingkan.stock-sync.stock-list.min-stock-count:3000}")
    private int minStockCount;

    public SinaStockListProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient,
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
        return "新浪财经";
    }

    @Override
    public List<StockBasicDto> fetchAllStocks() {
        Map<String, StockBasicDto> collected = new LinkedHashMap<>();
        int page = 1;
        boolean reachedEnd = false;
        while (page <= maxPages) {
            List<JsonNode> rows = requestPage(page, "hs_a");
            if (rows == null) {
                throw new StockListUnavailableException("新浪股票清单第 " + page + " 页获取失败");
            }
            if (rows.isEmpty()) {
                reachedEnd = true;
                break;
            }
            for (JsonNode row : rows) {
                StockBasicDto dto = parseRow(row);
                if (dto != null) {
                    collected.putIfAbsent(dto.exchange() + ":" + dto.stockCode(), dto);
                }
            }
            if (rows.size() < pageSize) {
                reachedEnd = true;
                break;
            }
            page++;
            sleep(requestIntervalMs);
        }
        if (!reachedEnd) {
            throw new StockListUnavailableException(
                    "新浪股票清单未在最大分页数内结束，拒绝使用可能不完整的结果");
        }
        if (collected.size() < minStockCount) {
            throw new StockListUnavailableException(
                    "新浪股票清单有效条数异常：" + collected.size() + " < " + minStockCount);
        }
        log.info("新浪股票清单完成：{} 条（{} 页）", collected.size(), page);
        return new ArrayList<>(collected.values());
    }

    @Override
    public List<StockBasicDto> fetchStocksByExchange(String exchange) {
        return fetchAllStocks().stream()
                .filter(dto -> dto.exchange().equalsIgnoreCase(exchange))
                .toList();
    }

    @Override
    public StockBasicDto fetchStock(String stockCode) {
        // 新浪列表接口不支持按代码查询，单只补录统一由东方财富完成
        return null;
    }

    private List<JsonNode> requestPage(int page, String node) {
        String url = String.format(URL_TEMPLATE, page, pageSize, node);
        for (int attempt = 1; attempt <= Math.max(1, maxRetries); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) LingKan/1.0")
                        .header("Referer", "https://finance.sina.com.cn")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn("新浪股票清单第 {} 页返回状态码 {}", page, response.statusCode());
                } else {
                    String body = response.body();
                    if (body == null || body.isBlank() || "null".equals(body.trim())) {
                        return List.of();
                    }
                    JsonNode root = objectMapper.readTree(body);
                    List<JsonNode> rows = new ArrayList<>();
                    if (root.isArray()) {
                        root.forEach(rows::add);
                    }
                    return rows;
                }
            } catch (Exception ex) {
                log.warn("新浪股票清单第 {} 页请求失败: {}", page, ex.getMessage());
            }
            sleep(1000L * attempt);
        }
        return null;
    }

    private StockBasicDto parseRow(JsonNode row) {
        String code = row.path("code").asText("");
        String name = row.path("name").asText("");
        if (!StockCodeRules.isAShareCode(code) || name.isBlank()) {
            return null;
        }
        return StockCodeRules.build(code, name, null, StockBasicDto.STATUS_LISTED, null);
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
