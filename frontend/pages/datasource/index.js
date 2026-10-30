/**
 * 数据源管理
 *
 * 后端 ProviderDto 不含任何密钥字段，前端也永远不展示 Token。
 * 连通性测试由后端发起真实请求，小程序只负责展示结果。
 */
const dataSourceApi = require('../../services/datasource');
const format = require('../../utils/format');

Page({
  data: {
    loading: true,
    providers: [],
    testing: ''
  },

  onLoad() {
    this.load();
  },

  noop() {},

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    return dataSourceApi
      .list()
      .then((list) => {
        const providers = (list || []).map((p) => {
          const meta = format.statusMeta(p.healthStatus);
          return {
            providerCode: p.providerCode,
            providerName: p.providerName,
            providerType: p.providerType || '',
            enabled: !!p.enabled,
            requiresToken: !!p.requiresToken,
            verified: !!p.verified,
            metricCount: p.metricCount || 0,
            healthStatus: p.healthStatus,
            healthText: meta.text,
            description: p.description || '',
            interval: p.requestIntervalMs ? p.requestIntervalMs + 'ms' : '—',
            lastSuccessText: p.lastSuccessAt ? format.timeAgo(p.lastSuccessAt) : '暂无',
            lastFailureText: p.lastFailureAt ? format.timeAgo(p.lastFailureAt) : '—'
          };
        });
        this.setData({ loading: false, providers: providers });
      })
      .catch(() => {
        this.setData({ loading: false });
      });
  },

  onTest(e) {
    const code = e.currentTarget.dataset.code;
    if (!code || this.data.testing) {
      return;
    }
    this.setData({ testing: code });
    dataSourceApi
      .test(code)
      .then((result) => {
        this.setData({ testing: '' });
        const ok = result && result.success;
        wx.showModal({
          title: ok ? '连接正常' : '连接失败',
          content:
            (result && result.message ? result.message : '无返回信息') +
            '\n耗时：' +
            ((result && result.latencyMs) || 0) +
            ' ms',
          showCancel: false,
          confirmColor: '#b3272d'
        });
        this.load();
      })
      .catch(() => {
        this.setData({ testing: '' });
      });
  },

  onToggle(e) {
    const dataset = e.currentTarget.dataset;
    const code = dataset.code;
    const enabled = dataset.enabled === true || dataset.enabled === 'true';
    if (!code) {
      return;
    }
    const action = enabled ? dataSourceApi.disable(code) : dataSourceApi.enable(code);
    action
      .then(() => {
        wx.showToast({ title: enabled ? '已停用' : '已启用', icon: 'success' });
        this.load();
      })
      .catch(() => {});
  },

  goLogs(e) {
    const dataset = e.currentTarget.dataset;
    if (!dataset.code) {
      return;
    }
    wx.navigateTo({
      url:
        '/pages/datasource-logs/index?provider=' +
        dataset.code +
        '&name=' +
        encodeURIComponent(dataset.name || dataset.code)
    });
  }
});
