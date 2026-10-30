package com.lingkan.datasource.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.datasource.provider.FinancialProvider;
import com.lingkan.datasource.provider.dto.FinancialItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 东方财富财报数据适配器。
 *
 * <p>接口：{@code https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_LICO_FN_CPD}
 * 实测（2026-10-30）：HTTP 200，贵州茅台返回正常，UTF-8 JSON。
 *
 * <p>字段口径（实测核对）：
 * <ul>
 *   <li>{@code QDATE}：报告期，如 {@code 2026Q3}；年报用 {@code 2025Q4} 表示；</li>
 *   <li>{@code DATEMMDD}：一季报/半年报/三季报/年报，用于推导 report_type；</li>
 *   <li>{@code TOTAL_OPERATE_INCOME}：营业收入（累计值，元）；</li>
 *   <li>{@code PARENT_NETPROFIT}：归母净利润（<b>累计值</b>，元），
 *       计算 TTM 时须先转单季值，禁止直接累加；</li>
 *   <li>{@code BASIC_EPS}：基本每股收益；{@code WEIGHTAVG_ROE}：加权 ROE（%）；</li>
 *   <li>{@code NOTICE_DATE}：披露日期；{@code REPORTDATE}：报告期截止日。</li>
 * </ul>
 *
 * <p>返回语义：接口失败（非 200 / 网络 / 解析异常）返回 null（MISSING），
 * 成功但无记录返回空列表，与 {@link FinancialProvider} 契约一致。
 */
@Component
public class EastMoneyFinancialProvider implements FinancialProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyFinancialProvider.class);
    public static final String CODE = "eastmoney";

    private static final String REPORT_NAME = "RPT_LICO_FN_CPD";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EastMoneyFinancialProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient,
                                      ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<FinancialItem> fetchFinancials(String stockCode) {
        String filter = "(SECURITY_CODE=\"" + clean(stockCode) + "\")";
        String url = "https://datacenter-web.eastmoney.com/api/data/v1/get"
                + "?reportName=" + REPORT_NAME
                + "&columns=ALL"
                + "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&pageNumber=1&pageSize=40";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 LingKan/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("东财财报接口返回状态码 {}", response.statusCode());
                return null;
            }
            return parse(stockCode, response.body());
        } catch (Exception ex) {
            log.warn("东财财报接口请求失败 {}: {}", stockCode, ex.getMessage());
            return null;
        }
    }

    static List<FinancialItem> parse(String stockCode, String body) {
        List<FinancialItem> items = new ArrayList<>();
        try {
            JsonNode root = new ObjectMapper().readTree(body);
            JsonNode result = root.get("result");
            if (result == null || result.isNull()) {
                return items;
            }
            JsonNode data = result.get("data");
            if (data == null || !data.isArray()) {
                return items;
            }
            for (JsonNode row : data) {
                FinancialItem item = toItem(row);
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (Exception ex) {
            log.warn("东财财报接口解析失败 {}: {}", stockCode, ex.getMessage());
        }
        // 按报告期升序返回，便于计算器做累计值→单季值换算
        items.sort(Comparator.comparing(FinancialItem::reportPeriod));
        return items;
    }

    private static FinancialItem toItem(JsonNode row) {
        String code = text(row, "SECURITY_CODE");
        String period = text(row, "QDATE");
        if (code.isBlank() || period.isBlank()) {
            return null;
        }
        String reportType = resolveType(text(row, "DATEMMDD"), period);
        return new FinancialItem(
                code,
                period,
                reportType,
                date(row, "REPORTDATE"),
                decimal(row, "TOTAL_OPERATE_INCOME"),
                decimal(row, "PARENT_NETPROFIT"),
                decimal(row, "BASIC_EPS"),
                decimal(row, "WEIGHTAVG_ROE"),
                date(row, "NOTICE_DATE")
        );
    }

    private static String resolveType(String dateMmdd, String period) {
        return switch (dateMmdd) {
            case "一季报" -> "Q1";
            case "半年报" -> "H1";
            case "三季报" -> "Q3";
            case "年报" -> "ANNUAL";
            default -> period.endsWith("Q4") ? "ANNUAL" : period.substring(period.indexOf('Q') + 1);
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        String raw = value.asText();
        return raw == null || raw.isBlank() || "-".equals(raw) ? "" : raw;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText();
        if (raw.isBlank() || "-".equals(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate date(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw.length() >= 10) {
            try {
                return LocalDate.parse(raw.substring(0, 10));
            } catch (Exception ignored) {
                // 格式异常按缺失处理
            }
        }
        return null;
    }

    private static String clean(String stockCode) {
        if (stockCode == null) {
            return "";
        }
        String code = stockCode.trim();
        int dot = code.indexOf('.');
        return dot > 0 ? code.substring(0, dot) : code;
    }
}
