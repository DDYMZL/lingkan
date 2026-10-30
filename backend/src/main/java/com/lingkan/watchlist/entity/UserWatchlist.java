package com.lingkan.watchlist.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人自选股。
 */
@Data
@TableName("user_watchlists")
public class UserWatchlist {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private Long stockId;
    private Integer sortOrder;
    private String remark;
    private LocalDateTime createdAt;
}
