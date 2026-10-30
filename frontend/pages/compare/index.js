/**
 * 单指标多数据源对比
 *
 * 原则：并列展示各来源的原始取值、时间与差异，不做平均、不做覆盖，
 *      用户可以在这里指定该指标的主数据源（写回指标-数据源绑定）。
 */
const stockApi = require('../../services/stock');
const metricsApi = require('../../services/metrics');
const format = require('../../utils/format');

Page({
  data: {
    code: '',
    metricCode: '',
    metricName: '',
    stockName: '',
    unit: '',
    selectedSource: '',
    sources: [],
    loading: true
  },

  onLoad(options) {
    const code = (options && options.code) || '';
    const metricCode = (options && options.metricCode) || '';
    const name = options && options.name ? decodeURIComponent(options.name) : '';
    if (!code || !metricCode) {
      this.setData({ loading: false });
      return;
    }
    this.setData({
      code: code,
      metricCode: metricCode,
      metricName: name
    });
    if (name) {
      wx.setNavigationBarTitle({ title: name + ' · 数据源对比' });
    }
    this.load();
  },

  onPullDownRefresh() {
    this.load().then(() => wx.stopPullDownRefresh());
  },

  load() {
    this.setData({ loading: true });
    return stockApi
      .metricSources(this.data.code, this.data.metricCode)
      .then((data) => {
        const dto = data || {};
        const sources = (dto.sources || []).map((s) => {
          const statusInfo = format.statusMeta(s.status);
          return {
            sourceCode: s.sourceCode,
            sourceName: s.sourceName,
            displayValue: s.displayValue || '--',
            unit: s.displayValue === '--' ? '' : s.unit || '',
            status: s.status,
            statusText: statusInfo.text,
            primary: !!s.primary,
            diffText:
              s.diffFromPrimary === null || s.diffFromPrimary === undefined
                ? ''
                : format.signed(s.diffFromPrimary, 4),
            dataTimeText: format.formatTime(s.dataTime),
            fetchTimeText: format.formatTime(s.fetchTime),
            hasValue: s.displayValue && s.displayValue !== '--'
          };
        });

        this.setData({
          loading: false,
          stockName: dto.stockName || '',
          metricName: dto.metricName || this.data.metricName,
          unit: dto.unit || '',
          selectedSource: dto.selectedSource || '',
          sources: sources
        });
      })
      .catch(() => {
        this.setData({ loading: false });
      });
  },

  switchPrimary(e) {
    const sourceCode = e.currentTarget.dataset.code;
    if (!sourceCode || sourceCode === this.data.selectedSource) {
      return;
    }
    wx.showModal({
      title: '切换主数据源',
      content: '将「' + sourceCode + '」设为该指标的主数据源？此设置对该指标全局生效。',
      confirmText: '确认切换',
      confirmColor: '#b3272d',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        metricsApi
          .switchPrimary(this.data.metricCode, sourceCode)
          .then(() => {
            wx.showToast({ title: '已切换', icon: 'success' });
            this.load();
          })
          .catch(() => {});
      }
    });
  }
});
