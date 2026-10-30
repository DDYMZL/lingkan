/**
 * 玲看股息 · 微信小程序（原生）
 * 全局只保留少量跨页共享状态，其余数据由各页面按需拉取。
 */
const config = require('./config/index');
const user = require('./utils/user');

App({
  globalData: {
    config: config,
    /** 用户在「配置 - 指标展示」里改动后置为 true，首页/详情页据此重新拉取偏好 */
    preferenceDirty: false,
    /** 指标定义缓存：metricCode -> definition */
    metricCategoryMap: null,
    launchedAt: 0
  },

  onLaunch() {
    // 单用户本地标识，不做注册登录（后端通过 X-LingKan-User 识别）
    user.ensureUserId();
    this.globalData.launchedAt = Date.now();
  },

  onError(err) {
    console.error('[lingkan] uncaught error:', err);
  },

  onUnhandledRejection(res) {
    console.warn('[lingkan] unhandled rejection:', res && res.reason);
  }
});
