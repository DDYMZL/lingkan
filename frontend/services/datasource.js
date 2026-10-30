/**
 * 数据源管理
 * 后端：com.lingkan.datasource.DataSourceController
 * 注意：ProviderDto 不含任何 Token 字段，前端也不应展示密钥。
 */
const { http } = require('../utils/request');

module.exports = {
  list() {
    return http.get('/data-sources');
  },

  get(providerCode) {
    return http.get('/data-sources/' + providerCode);
  },

  /** 全部指标-数据源绑定（一次拉取，供指标配置页复用） */
  bindings() {
    return http.get('/data-sources/bindings', null, { toastError: false });
  },

  /** 连通性测试：由后端发起真实请求 */
  test(providerCode) {
    return http.post('/data-sources/' + providerCode + '/test', null, {
      loading: true,
      loadingText: '测试中'
    });
  },

  enable(providerCode) {
    return http.post('/data-sources/' + providerCode + '/enable');
  },

  disable(providerCode) {
    return http.post('/data-sources/' + providerCode + '/disable');
  },

  logs(providerCode, limit) {
    return http.get('/data-sources/' + providerCode + '/logs', { limit: limit || 20 });
  }
};
