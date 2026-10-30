package com.lingkan.websocket;

import com.lingkan.calculation.dto.BoardRowDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 首页行情变化消息；目标价不属于行情推送负载。 */
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
            LocalDateTime quoteTime,
            String dataStatus) {

        public static QuoteRow from(BoardRowDto row) {
            return new QuoteRow(row.stockCode(), row.price(), row.changePercent(), row.changeAmount(),
                    row.annualDividendPerShare(), row.dividendYieldTtm(), row.peTtm(), row.peStatus(),
                    row.quoteTime(), row.dataStatus());
        }
    }
}
