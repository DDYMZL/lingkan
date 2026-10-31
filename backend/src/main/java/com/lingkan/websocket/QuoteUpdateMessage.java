package com.lingkan.websocket;

import com.lingkan.calculation.dto.BoardRowDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 首页行情变化消息；目标价不属于行情推送负载（只随分红变化，看板快照才带）。
 *
 * <p>周线五列<b>在</b>推送负载内：本周未收盘周线的收盘价用实时价覆盖（spec 007 Q7-8），
 * 因此 60周/120周/周上/周中/周下随行情变化，必须随推送下发（spec 010 §5.2）。
 */
public record QuoteUpdateMessage(String type, List<QuoteRow> data) {

    public static final String TYPE = "STOCK_QUOTE_UPDATE";

    public static QuoteUpdateMessage fromRows(List<BoardRowDto> rows) {
        return new QuoteUpdateMessage(TYPE, rows.stream().map(QuoteRow::from).toList());
    }

    public record QuoteRow(
            String stockCode,
            BigDecimal price,
            BigDecimal changePercent,
            BigDecimal changeAmount,
            BigDecimal annualDividendPerShare,
            BigDecimal dividendYieldTtm,
            BigDecimal peTtm,
            String peStatus,
            BigDecimal ma60w,
            BigDecimal ma120w,
            BigDecimal weekUpper,
            BigDecimal weekMiddle,
            BigDecimal weekLower,
            String weeklyStatus,
            LocalDate weeklyDataTime,
            LocalDateTime quoteTime,
            String dataStatus) {

        public static QuoteRow from(BoardRowDto row) {
            return new QuoteRow(row.stockCode(), row.price(), row.changePercent(), row.changeAmount(),
                    row.annualDividendPerShare(), row.dividendYieldTtm(), row.peTtm(), row.peStatus(),
                    row.ma60w(), row.ma120w(), row.weekUpper(), row.weekMiddle(), row.weekLower(),
                    row.weeklyStatus(), row.weeklyDataTime(), row.quoteTime(), row.dataStatus());
        }
    }
}
