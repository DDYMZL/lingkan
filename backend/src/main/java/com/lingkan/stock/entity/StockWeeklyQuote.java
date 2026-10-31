package com.lingkan.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史周线。adjustment_type: NONE / QFQ / HFQ；看板 v1.2 只写 QFQ（东财 fqt=1 前复权）。
 *
 * <p>trade_date 为该周最后一个交易日（东财周 K 接口返回口径）。前复权基准会随最新除权事件整体漂移，
 * 因此同步时按整窗口覆盖 upsert，禁止只增量追加（spec 010 §2.4）。
 */
@Data
@TableName("stock_weekly_quotes")
public class StockWeeklyQuote {

    /** 复权方式：前复权（东财 fqt=1）。看板周线五指标固定用前复权，与现价同口径可比。 */
    public static final String ADJUSTMENT_QFQ = "QFQ";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;
    private LocalDate tradeDate;
    private BigDecimal openPrice;
    private BigDecimal closePrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal volume;
    private BigDecimal amount;
    private String adjustmentType;
    private String sourceCode;
    private LocalDateTime fetchTime;
    private LocalDateTime createdAt;
}
