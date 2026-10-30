package com.lingkan.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.stock.entity.Stock;
import com.lingkan.sync.dto.StockBasicDto;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    /**
     * 批量 Upsert 股票基础信息。
     *
     * <p>方言说明：openGauss 7.0 不支持 PostgreSQL 的 {@code ON CONFLICT} 语法
     * （实测报 syntax error at or near "conflict"），等价写法为
     * {@code ON DUPLICATE KEY UPDATE}，已在本项目实测通过。
     *
     * <p>空值保护：行业、市场、板块、上市日期只在本次取到有效值时才覆盖，
     * 避免上游字段缺失时把已有有效数据刷成空；同时保留自增主键 id，
     * 不影响自选股、行情、指标等外键关联。
     */
    @Insert("""
            <script>
            INSERT INTO stocks
                (stock_code, stock_name, exchange, market, board, industry, listing_date,
                 stock_status, is_st, created_at, updated_at)
            VALUES
            <foreach collection="list" item="s" separator=",">
                (#{s.stockCode}, #{s.stockName}, #{s.exchange}, #{s.market}, #{s.board},
                 #{s.industry,jdbcType=VARCHAR}, #{s.listingDate,jdbcType=DATE},
                 #{s.stockStatus}, #{s.isSt}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            </foreach>
            ON DUPLICATE KEY UPDATE
                stock_name   = excluded.stock_name,
                market       = COALESCE(NULLIF(excluded.market, ''), stocks.market),
                board        = COALESCE(NULLIF(excluded.board, ''), stocks.board),
                industry     = COALESCE(NULLIF(excluded.industry, ''), stocks.industry),
                listing_date = COALESCE(excluded.listing_date, stocks.listing_date),
                stock_status = excluded.stock_status,
                is_st        = excluded.is_st,
                updated_at   = CURRENT_TIMESTAMP
            </script>
            """)
    int upsertBatch(@Param("list") List<StockBasicDto> list);

    /** 查询已存在代码，用于区分新增与更新数量（只读，不影响写入语义） */
    @Select("""
            <script>
            SELECT stock_code FROM stocks WHERE stock_code IN
            <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
            </script>
            """)
    List<String> selectExistingCodes(@Param("codes") List<String> codes);

    /** 同步任务用的轻量投影：只取编排需要的列，避免全字段查询 */
    @Select("""
            SELECT id, stock_code, stock_name, exchange, market, board, industry, listing_date,
                   stock_status, is_st
            FROM stocks
            WHERE stock_status = 'LISTED'
            ORDER BY stock_code
            """)
    List<Stock> selectListedForSync();

    /** 按代码批量查询轻量投影 */
    @Select("""
            <script>
            SELECT id, stock_code, stock_name, exchange, market, board, industry, listing_date,
                   stock_status, is_st
            FROM stocks WHERE stock_code IN
            <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
            </script>
            """)
    List<Stock> selectByCodes(@Param("codes") List<String> codes);
}
