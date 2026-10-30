/**
 * 数据采集日志
 */
const dataSourceApi = require('../../services/datasource');
const format = require('../../utils/format');

const SYNC_TYPE_TEXT = {
  QUOTE: '行情',
  DAILY: '日线',
  FINANCIAL: '财务',
  DIVIDEND: '分红',
  METRIC: '指标'
};

Page({
  data: {
    provider: '',
    providerName: '',
    loading: true,
    logs: []
  },

  onLoad(options) {
    const provider = (options && options.provider) || '';
    const name = options && options.name ? decodeURIComponent(options.name) : provider;
    this.setData({ provider: provider, providerName: name });
    if (name) {
      wx.setNavigationBarTitle({ title: name + ' · 采集日志' });
    }
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    if (!this.data.provider) {
      this.setData({ loading: false });
      return Promise.resolve();
    }
    return dataSourceApi
      .logs(this.data.provider, 30)
      .then((list) => {
        const logs = (list || []).map((log) => {
          const meta = format.statusMeta(log.syncStatus);
          return {
            id: log.id,
            syncType: SYNC_TYPE_TEXT[log.syncType] || log.syncType || '—',
            metricCode: log.metricCode || '',
            startedText: format.formatTime(log.startedAt),
            finishedText: log.finishedAt ? format.formatTime(log.finishedAt) : '进行中',
            total: log.totalCount || 0,
            success: log.successCount || 0,
            failed: log.failedCount || 0,
            syncStatus: log.syncStatus,
            statusText: meta.text,
            errorMessage: log.errorMessage || ''
          };
        });
        this.setData({ loading: false, logs: logs });
      })
      .catch(() => {
        this.setData({ loading: false });
      });
  }
});
