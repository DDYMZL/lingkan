/**
 * 用户指标偏好（展示可配置）
 * 后端：com.lingkan.metrics.UserMetricPreferenceController
 */
const { http } = require('../utils/request');

module.exports = {
  list() {
    return http.get('/user/metric-preferences', null, { toastError: false });
  },

  /** 整量保存 */
  save(preferences) {
    return http.put('/user/metric-preferences', { preferences: preferences }, { loading: true, loadingText: '保存中' });
  },

  /** 重置为系统默认 */
  reset() {
    return http.post('/user/metric-preferences/reset', null, { loading: true, loadingText: '重置中' });
  }
};
