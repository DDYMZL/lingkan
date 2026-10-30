package com.lingkan.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票基础信息。
 */
@Data
@TableName("stocks")
public class Stock {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stockCode;
    private String stockName;
    /** SH / SZ / BJ */
    private String exchange;
    private String market;
    private String board;
    private String industry;
    private LocalDate listingDate;
    private LocalDate delistingDate;
    private String stockStatus;
    private Boolean isSt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
