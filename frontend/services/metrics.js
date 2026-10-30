/**
 * 指标定义与指标-数据源绑定
 * 后端：com.lingkan.metrics.MetricController
 */
const { http } = require('../utils/request');

module.exports = {
  /** 全部指标定义 */
  list() {
    return http.get('/metrics');
  },

  /** 按分类聚合（带 categoryName，用于详情页分组） */
  categories() {
    return http.get('/metrics/categories');
  },

  /** 单指标定义 */
  get(metricCode) {
    return http.get('/metrics/' + metricCode);
  },

  /** 指标绑定的数据源 */
  sourcesOf(metricCode) {
    return http.get('/metrics/' + metricCode + '/sources');
  },

  /** 切换指标主数据源 */
  switchPrimary(metricCode, providerCode) {
    return http.put('/metrics/' + metricCode + '/sources', { providerCode: providerCode });
  }
};
