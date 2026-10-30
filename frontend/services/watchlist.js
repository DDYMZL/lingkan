/**
 * 自选股
 * 后端：com.lingkan.watchlist.WatchlistController
 */
const { http } = require('../utils/request');

module.exports = {
  list(page, size) {
    return http.get('/watchlist', { page: page || 1, size: size || 50 });
  },

  add(stockCode, remark) {
    return http.post('/watchlist', { stockCode: stockCode, remark: remark || '' });
  },

  remove(stockCode) {
    return http.del('/watchlist/' + stockCode);
  },

  sort(stockCodes) {
    return http.put('/watchlist/sort', { stockCodes: stockCodes });
  },

  remark(stockCode, remark) {
    return http.put('/watchlist/' + stockCode + '/remark', { remark: remark });
  },

  /** 刷新自选股行情（异步任务） */
  refresh() {
    return http.post('/watchlist/refresh', null, { toastError: false });
  }
};
