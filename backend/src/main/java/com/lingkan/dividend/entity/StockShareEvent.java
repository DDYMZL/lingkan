package com.lingkan.dividend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股本变动事件（送股 / 转增 / 拆股 / 并股）。
 *
 * <p>用途：历史分红之后发生股本变动时，把当时的每股现金分红换算为当前股本口径
 * （见 spec 010 业务规则 3.2 与 ShareBasisAdjuster）。
 *
 * <p>比例口径：送转比例为「每 10 股送/转 X 股 → X/10」；
 * 拆股为「1 拆 N → N」；并股为「N 并 1 → 1/N」。
 */
@Data
@TableName("stock_share_events")
public class StockShareEvent {

    public static final String TYPE_BONUS = "BONUS";
    public static final String TYPE_TRANSFER = "TRANSFER";
    public static final String TYPE_SPLIT = "SPLIT";
    public static final String TYPE_REVERSE_SPLIT = "REVERSE_SPLIT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;

    /** 除权除息日（送转生效日） */
    private LocalDate eventDate;

    private String eventType;

    private BigDecimal bonusRatio;
    private BigDecimal transferRatio;
    private BigDecimal splitRatio;

    private String sourceCode;
    private LocalDateTime createdAt;
}
