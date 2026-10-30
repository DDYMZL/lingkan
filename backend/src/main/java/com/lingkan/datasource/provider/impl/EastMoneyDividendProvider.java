package com.lingkan.datasource.provider.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingkan.common.util.DecimalUtils;
import com.lingkan.datasource.provider.DividendProvider;
import com.lingkan.datasource.provider.dto.DividendItem;
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
import java.util.List;

/**
 * 东方财富分红送配适配器。
 *
 * <p>接口：{@code https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_SHAREBONUS_DET}
 * 实测（2026-10-30）：HTTP 200，贵州茅台/新易盛返回正常，UTF-8 JSON。
 *
 * <p>字段口径（实测核对）：
 * <ul>
 *   <li>{@code PRETAX_BONUS_RMB}：每 <b>10 股</b>派息金额（元，税前），需 ÷10 得每股；</li>
 *   <li>{@code EX_DIVIDEND_DATE}：除权除息日；{@code EQUITY_RECORD_DATE}：股权登记日；</li>
 *   <li>{@code ASSIGN_PROGRESS}：方案进度（「实施分配」= 已实施；「预案」= 仅预案）；</li>
 *   <li>{@code BONUS_RATIO}：每 10 股送股数；{@code IT_RATIO}：每 10 股转增数；
 *       {@code BONUS_IT_RATIO}：送+转合计（两者缺省时的兜底）；</li>
 *   <li>接口<b>无派息日字段</b>：payment_date / implementation_date 一律为 null，
 *       TTM 窗口由计算引擎按除息日兜底（见 spec 010 §3.1）；</li>
 *   <li>{@code REPORT_DATE}：分红归属年度报告期。</li>
 * </ul>
 */
@Component
public class EastMoneyDividendProvider implements DividendProvider {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyDividendProvider.class);
    public static final String CODE = "eastmoney";

    private static final String REPORT_NAME = "RPT_SHAREBONUS_DET";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EastMoneyDividendProvider(@Qualifier("dataSourceHttpClient") HttpClient httpClient,
                                     ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<DividendItem> fetchDividends(String stockCode) {
        String filter = "(SECURITY_CODE=\"" + clean(stockCode) + "\")";
        String url = "https://datacenter-web.eastmoney.com/api/data/v1/get"
                + "?reportName=" + REPORT_NAME
                + "&columns=ALL"
                + "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&pageNumber=1&pageSize=60"
                + "&sortTypes=-1&sortColumns=EX_DIVIDEND_DATE";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 LingKan/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("东财分红接口返回状态码 {}", response.statusCode());
                return List.of();
            }
            return parse(stockCode, response.body());
        } catch (Exception ex) {
            log.warn("东财分红接口请求失败 {}: {}", stockCode, ex.getMessage());
            return List.of();
        }
    }

    static List<DividendItem> parse(String stockCode, String body) {
        List<DividendItem> items = new ArrayList<>();
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
                DividendItem item = toItem(row);
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (Exception ex) {
            log.warn("东财分红接口解析失败 {}: {}", stockCode, ex.getMessage());
        }
        return items;
    }

    private static DividendItem toItem(JsonNode row) {
        String code = text(row, "SECURITY_CODE");
        if (code.isBlank()) {
            return null;
        }
        LocalDate exDividendDate = date(row, "EX_DIVIDEND_DATE");
        LocalDate recordDate = date(row, "EQUITY_RECORD_DATE");
        LocalDate planNoticeDate = date(row, "PLAN_NOTICE_DATE");
        String reportDate = text(row, "REPORT_DATE");

        // 方案唯一标识：证券 + 归属报告期 + 除息日（无除息日按登记日/公告日兜底）
        String anchor = exDividendDate != null ? exDividendDate.toString()
                : recordDate != null ? recordDate.toString()
                : planNoticeDate != null ? planNoticeDate.toString()
                : reportDate;
        String dividendId = CODE + ":" + text(row, "SECUCODE") + ":" + reportDate + ":" + anchor;

        // 每 10 股派息 → 每股
        BigDecimal perShare = null;
        BigDecimal pretax = decimal(row, "PRETAX_BONUS_RMB");
        if (pretax != null) {
            perShare = pretax.divide(BigDecimal.TEN, DecimalUtils.MC);
        }

        return new DividendItem(
                code,
                dividendId,
                yearOf(reportDate, planNoticeDate),
                perShare,
                planNoticeDate,
                recordDate,
                exDividendDate,
                null,   // 数据源无派息日字段
                null,   // 数据源无实施日期字段（TTM 按除息日兜底）
                text(row, "ASSIGN_PROGRESS"),
                bonusRatio(row),
                transferRatio(row),
                text(row, "IMPL_PLAN_PROFILE")
        );
    }

    /** 每 10 股送股数 ÷ 10；无送股返回 null */
    private static BigDecimal bonusRatio(JsonNode row) {
        BigDecimal bonus = decimal(row, "BONUS_RATIO");
        return positive(bonus) ? bonus.divide(BigDecimal.TEN, DecimalUtils.MC) : null;
    }

    /**
     * 每 10 股转增数 ÷ 10；无转增返回 null。
     * IT_RATIO 缺失时用 BONUS_IT_RATIO（送+转合计）减去送股数兜底。
     */
    private static BigDecimal transferRatio(JsonNode row) {
        BigDecimal transfer = decimal(row, "IT_RATIO");
        if (!positive(transfer)) {
            BigDecimal total = decimal(row, "BONUS_IT_RATIO");
            if (positive(total)) {
                BigDecimal bonus = decimal(row, "BONUS_RATIO");
                transfer = total.subtract(bonus == null ? BigDecimal.ZERO : bonus);
            }
        }
        return positive(transfer) ? transfer.divide(BigDecimal.TEN, DecimalUtils.MC) : null;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String yearOf(String reportDate, LocalDate fallback) {
        if (reportDate != null && reportDate.length() >= 4) {
            return reportDate.substring(0, 4);
        }
        return fallback == null ? null : String.valueOf(fallback.getYear());
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
