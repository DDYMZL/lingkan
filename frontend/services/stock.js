/**
 * 股票相关接口
 * 后端：com.lingkan.stock.StockController
 */
const { http } = require('../utils/request');

module.exports = {
  /** 全市场分页列表（字段由指标配置决定） */
  list(params) {
    return http.get('/stocks', params);
  },

  /** 关键词搜索 */
  search(keyword, limit) {
    return http.get('/stocks/search', { keyword: keyword, limit: limit || 20 });
  },

  /** 个股详情：基础信息 + 行情 + 动态指标 */
  detail(stockCode) {
    return http.get('/stocks/' + stockCode);
  },

  /** 实时行情 */
  quote(stockCode) {
    return http.get('/stocks/' + stockCode + '/quote', null, { toastError: false });
  },

  /** 该股票全部已启用指标 */
  metrics(stockCode) {
    return http.get('/stocks/' + stockCode + '/metrics');
  },

  /** 单指标历史序列 */
  history(stockCode, metricCode, range) {
    return http.get('/stocks/' + stockCode + '/history', {
      metricCode: metricCode,
      range: range || '1Y'
    });
  },

  /** 多指标历史序列 */
  historyBatch(stockCode, metricCodes, range) {
    return http.get('/stocks/' + stockCode + '/history/batch', {
      metricCodes: (metricCodes || []).join(','),
      range: range || '1Y'
    });
  },

  /** 分红记录 */
  dividends(stockCode) {
    return http.get('/stocks/' + stockCode + '/dividends', null, { toastError: false });
  },

  /** 单指标多数据源对比 */
  metricSources(stockCode, metricCode) {
    return http.get('/stocks/' + stockCode + '/metrics/' + metricCode + '/sources');
  },

  /** 手动刷新该股票数据（异步任务） */
  refresh(stockCode) {
    return http.post('/stocks/' + stockCode + '/refresh');
  }
};
