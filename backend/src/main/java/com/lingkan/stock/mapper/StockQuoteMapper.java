package com.lingkan.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.stock.entity.StockQuote;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StockQuoteMapper extends BaseMapper<StockQuote> {

    /**
     * 批量 Upsert 最新行情（stock_quotes 语义为"按数据源保留一条最新快照"）。
     *
     * <p>唯一键 (stock_id, source_code)；单次同步 5000+ 只股票时逐条 select-then-write
     * 会产生上万次查询，故改为一次批量语句。
     * 字段为 null 时保留原值（COALESCE），避免个别字段缺失把有效行情刷空。
     */
    @Insert("""
            <script>
            INSERT INTO stock_quotes
                (stock_id, latest_price, change_percent, change_amount, open_price, high_price,
                 low_price, pre_close, volume, amount, turnover_rate, total_market_cap,
                 source_code, data_time, fetch_time)
            VALUES
            <foreach collection="list" item="q" separator=",">
                (#{q.stockId}, #{q.latestPrice,jdbcType=NUMERIC}, #{q.changePercent,jdbcType=NUMERIC},
                 #{q.changeAmount,jdbcType=NUMERIC}, #{q.openPrice,jdbcType=NUMERIC},
                 #{q.highPrice,jdbcType=NUMERIC}, #{q.lowPrice,jdbcType=NUMERIC},
                 #{q.preClose,jdbcType=NUMERIC}, #{q.volume,jdbcType=NUMERIC},
                 #{q.amount,jdbcType=NUMERIC}, #{q.turnoverRate,jdbcType=NUMERIC},
                 #{q.totalMarketCap,jdbcType=NUMERIC}, #{q.sourceCode},
                 #{q.dataTime,jdbcType=TIMESTAMP}, #{q.fetchTime,jdbcType=TIMESTAMP})
            </foreach>
            ON DUPLICATE KEY UPDATE
                latest_price      = COALESCE(excluded.latest_price, stock_quotes.latest_price),
                change_percent    = COALESCE(excluded.change_percent, stock_quotes.change_percent),
                change_amount     = COALESCE(excluded.change_amount, stock_quotes.change_amount),
                open_price        = COALESCE(excluded.open_price, stock_quotes.open_price),
                high_price        = COALESCE(excluded.high_price, stock_quotes.high_price),
                low_price         = COALESCE(excluded.low_price, stock_quotes.low_price),
                pre_close         = COALESCE(excluded.pre_close, stock_quotes.pre_close),
                volume            = COALESCE(excluded.volume, stock_quotes.volume),
                amount            = COALESCE(excluded.amount, stock_quotes.amount),
                turnover_rate     = COALESCE(excluded.turnover_rate, stock_quotes.turnover_rate),
                total_market_cap  = COALESCE(excluded.total_market_cap, stock_quotes.total_market_cap),
                data_time         = COALESCE(excluded.data_time, stock_quotes.data_time),
                fetch_time        = excluded.fetch_time
            </script>
            """)
    int upsertBatch(@Param("list") List<StockQuote> list);
}
