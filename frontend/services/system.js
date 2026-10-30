/**
 * 市场与系统状态接口
 */
const { http } = require('../utils/request');

module.exports = {
  /** 市场概览（available=false 时不得展示任何估算值） */
  overview() {
    return http.get('/market/overview', null, { toastError: false });
  },

  /** 触发全市场刷新（后端可能未启用，会返回 accepted=false） */
  refresh() {
    return http.post('/market/refresh', null, { toastError: false });
  },

  /** 数据状态与交易时段 */
  dataStatus() {
    return http.get('/system/data-status', null, { toastError: false });
  }
};
