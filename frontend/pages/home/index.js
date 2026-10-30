/**
 * 首页：我的自选 + 市场概览 + 数据状态
 *
 * 数据可追溯原则：
 * - 市场概览 available=false 时不展示任何估算值；
 * - 每个指标值都来自后端 MetricValueDto，前端不做二次计算。
 */
const systemApi = require('../../services/system');
const watchlistApi = require('../../services/watchlist');
const preferenceApi = require('../../services/preference');
const metricUtil = require('../../utils/metrics');
const format = require('../../utils/format');

const app = getApp();

Page({
  data: {
    loading: true,
    rows: [],
    total: 0,
    columns: [],
    columnHint: '',
    market: null,
    marketBar: null,
    phaseText: '--',
    trading: false,
    syncTimeText: '--',
    refreshingQuotes: false
  },

  onLoad() {
    this.loadAll();
  },

  onShow() {
    if (app.globalData.preferenceDirty) {
      app.globalData.preferenceDirty = false;
      this.loadAll();
    }
  },

  onPullDownRefresh() {
    this.loadAll(true);
  },

  /** 下拉刷新：先触发后端异步刷新行情，再拉取列表 */
  onRefreshQuotes() {
    if (this.data.refreshingQuotes) {
      return;
    }
    this.setData({ refreshingQuotes: true });
    watchlistApi
      .refresh()
      .then((res) => {
        wx.showToast({
          title: '已提交刷新（' + ((res && res.count) || 0) + ' 只）',
          icon: 'none'
        });
        // 后端为异步任务，稍后再拉一次数据
        setTimeout(() => this.loadAll(true), 1600);
      })
      .catch(() => {})
      .then(() => {
        this.setData({ refreshingQuotes: false });
      });
  },

  loadAll(isPull) {
    if (!isPull) {
      this.setData({ loading: true });
    }

    return Promise.all([
      systemApi.overview().catch(() => null),
      systemApi.dataStatus().catch(() => null),
      watchlistApi.list(1, 50).catch(() => ({ list: [], total: 0 })),
      preferenceApi.list().catch(() => [])
    ])
      .then((res) => {
        const overview = res[0];
        const status = res[1];
        const page = res[2] || { list: [], total: 0 };
        const preferences = res[3] || [];

        const columns = metricUtil.watchlistColumns(preferences);
        const rows = (page.list || []).map((item) => this.buildRow(item, columns));

        this.setData({
          loading: false,
          rows: rows,
          total: page.total || rows.length,
          columns: columns,
          columnHint: columns.length ? '' : '未配置自选页展示列，当前默认显示每只股票的前 3 个指标',
          market: overview && overview.available ? overview : null,
          marketBar: overview && overview.available ? this.buildMarketBar(overview) : null,
          phaseText: (status && status.marketPhase) || '--',
          trading: !!(status && status.tradingDay),
          syncTimeText: status && status.lastMarketSyncAt
            ? format.timeAgo(status.lastMarketSyncAt)
            : '暂无采集记录'
        });

        wx.stopPullDownRefresh();
      })
      .catch(() => {
        this.setData({ loading: false });
        wx.stopPullDownRefresh();
      });
  },

  buildRow(item, columns) {
    const metrics = item.metrics || {};
    const quote = metricUtil.priceOf(metrics);
    return {
      code: item.stockCode,
      name: item.stockName,
      exchange: item.exchange || '',
      industry: item.industry || '',
      st: !!item.st,
      price: quote.price,
      changeText: quote.changeText,
      tone: quote.tone,
      cells: metricUtil.buildCells(metrics, columns, 3),
      dataTimeText: quote.dataTime ? format.timeAgo(quote.dataTime) : ''
    };
  },

  buildMarketBar(overview) {
    const up = overview.upCount || 0;
    const down = overview.downCount || 0;
    const flat = overview.flatCount || 0;
    const total = up + down + flat;
    if (!total) {
      return null;
    }
    return {
      up: ((up / total) * 100).toFixed(2),
      down: ((down / total) * 100).toFixed(2),
      flat: ((flat / total) * 100).toFixed(2)
    };
  },

  goDetail(e) {
    const code = e.currentTarget.dataset.code;
    if (!code) {
      return;
    }
    wx.navigateTo({ url: '/pages/stock/index?code=' + code });
  },

  goSearch() {
    wx.navigateTo({ url: '/pages/search/index' });
  },

  goRanking() {
    wx.switchTab({ url: '/pages/ranking/index' });
  }
});
