/**
 * 我的：版本信息、数据来源、使用边界与免责声明
 */
const dataSourceApi = require('../../services/datasource');
const systemApi = require('../../services/system');
const config = require('../../config/index');
const format = require('../../utils/format');
const user = require('../../utils/user');

Page({
  data: {
    version: config.version,
    env: config.env,
    baseUrl: config.baseUrl,
    userId: '',
    sources: [],
    loading: true,
    statusText: '--'
  },

  onLoad() {
    this.setData({ userId: user.getUserId() });
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    return Promise.all([
      dataSourceApi.list().catch(() => []),
      systemApi.dataStatus().catch(() => null)
    ])
      .then((res) => {
        const providers = res[0] || [];
        const status = res[1];

        const sources = providers.map((p) => {
          const meta = format.statusMeta(p.healthStatus);
          return {
            providerCode: p.providerCode,
            providerName: p.providerName,
            enabled: !!p.enabled,
            verified: !!p.verified,
            metricCount: p.metricCount || 0,
            healthStatus: p.healthStatus,
            healthText: meta.text,
            description: p.description || ''
          };
        });

        this.setData({
          loading: false,
          sources: sources,
          statusText: (status && status.marketPhase) || '--'
        });
      })
      .catch(() => {
        this.setData({ loading: false });
      });
  },

  onCopyBaseUrl() {
    wx.setClipboardData({ data: this.data.baseUrl });
  },

  onClearCache() {
    wx.showModal({
      title: '清空本地缓存',
      content: '将清除本地用户标识与页面缓存，不会影响后端数据。',
      confirmText: '清空',
      confirmColor: '#b3272d',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        wx.clearStorageSync();
        user.ensureUserId();
        this.setData({ userId: user.getUserId() });
        wx.showToast({ title: '已清空', icon: 'success' });
      }
    });
  }
});
