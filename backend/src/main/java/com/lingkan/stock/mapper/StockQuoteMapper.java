package com.lingkan.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingkan.stock.entity.StockQuote;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StockQuoteMapper extends BaseMapper<StockQuote> {
}
