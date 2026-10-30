/**
 * 配置中心：指标展示 / 数据源 / 数据状态
 */
const systemApi = require('../../services/system');
const metricsApi = require('../../services/metrics');
const preferenceApi = require('../../services/preference');
const format = require('../../utils/format');

Page({
  data: {
    loading: true,
    metricTotal: 0,
    metricEnabled: 0,
    pinnedCount: 0,
    collections: [],
    phaseText: '--',
    serverTimeText: '--'
  },

  onShow() {
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    return Promise.all([
      metricsApi.list().catch(() => []),
      preferenceApi.list().catch(() => []),
      systemApi.dataStatus().catch(() => null)
    ]).then((res) => {
      const defs = res[0] || [];
      const prefs = res[1] || [];
      const status = res[2];

      const metricTotal = defs.length;
      const metricEnabled = prefs.filter((p) => p.enabled !== false).length;
      const pinnedCount = prefs.filter(
        (p) => p.enabled !== false && (p.showInHome || p.showInWatchlist)
      ).length;

      const collections = ((status && status.collections) || []).map((c) => {
        const meta = format.statusMeta(c.healthStatus);
        return {
          providerCode: c.providerCode,
          providerName: c.providerName,
          healthStatus: c.healthStatus,
          healthText: meta.text,
          tone: meta.tone,
          message: c.message || '',
          lastSuccessText: c.lastSuccessAt ? format.timeAgo(c.lastSuccessAt) : '暂无'
        };
      });

      this.setData({
        loading: false,
        metricTotal: metricTotal,
        metricEnabled: metricEnabled,
        pinnedCount: pinnedCount,
        collections: collections,
        phaseText: (status && status.marketPhase) || '--',
        serverTimeText: status && status.serverTime ? format.formatTime(status.serverTime) : '--'
      });
    });
  },

  goMetrics() {
    wx.navigateTo({ url: '/pages/config-metrics/index' });
  },

  goDataSource() {
    wx.navigateTo({ url: '/pages/datasource/index' });
  },

  goAbout() {
    wx.switchTab({ url: '/pages/about/index' });
  }
});
