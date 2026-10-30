package com.lingkan.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.stock.entity.StockDailyQuote;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockDailyQuoteMapper extends BaseMapper<StockDailyQuote> {
}
