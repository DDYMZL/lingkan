/**
 * 全市场：搜索 + 按指标排序 + 分页加载
 *
 * 排序项来自用户已启用的指标（sortable 由偏好控制），前端不写死任何指标。
 */
const stockApi = require('../../services/stock');
const preferenceApi = require('../../services/preference');
const metricsApi = require('../../services/metrics');
const metricUtil = require('../../utils/metrics');

const PAGE_SIZE = 20;

Page({
  data: {
    keyword: '',
    exchange: '',
    exchangeOptions: [
      { code: '', name: '全部' },
      { code: 'SH', name: '沪市' },
      { code: 'SZ', name: '深市' },
      { code: 'BJ', name: '北交所' }
    ],
    sortMetric: '',
    sortOrder: 'desc',
    sortOptions: [],
    columns: [],
    rows: [],
    total: 0,
    page: 1,
    loading: true,
    loadingMore: false,
    hasMore: true
  },

  onLoad() {
    this.loadPreferences().then(() => this.loadPage(1));
  },

  onPullDownRefresh() {
    this.loadPage(1).then(() => wx.stopPullDownRefresh());
  },

  onReachBottom() {
    if (this.data.loading || this.data.loadingMore || !this.data.hasMore) {
      return;
    }
    this.loadPage(this.data.page + 1);
  },

  onUnload() {
    if (this._timer) {
      clearTimeout(this._timer);
      this._timer = null;
    }
  },

  /** 指标偏好决定列表展示列与可排序项（无偏好时用指标库的启用项兜底） */
  loadPreferences() {
    return Promise.all([
      preferenceApi.list().catch(() => []),
      metricsApi.list().catch(() => [])
    ]).then((res) => {
      const prefs = res[0] || [];
      const defs = res[1] || [];

      const nameMap = {};
      defs.forEach((d) => {
        nameMap[d.metricCode] = d.metricName;
      });

      const enabled = prefs
        .filter((p) => p.enabled !== false && !metricUtil.isBasic(p.metricCode))
        .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));

      let sortOptions = enabled
        .filter((p) => p.sortable !== false)
        .map((p) => ({
          code: p.metricCode,
          name: p.displayName || nameMap[p.metricCode] || p.metricCode
        }));

      if (!sortOptions.length) {
        sortOptions = defs
          .filter((d) => d.enabled && !metricUtil.isBasic(d.metricCode))
          .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
          .slice(0, 8)
          .map((d) => ({ code: d.metricCode, name: d.metricName }));
      }

      this.setData({
        columns: metricUtil.watchlistColumns(prefs),
        sortOptions: sortOptions
      });
    });
  },

  loadPage(targetPage) {
    const isFirst = targetPage <= 1;
    this.setData({
      loading: isFirst,
      loadingMore: !isFirst
    });

    const params = {
      page: targetPage,
      size: PAGE_SIZE,
      keyword: this.data.keyword,
      exchange: this.data.exchange,
      sortMetric: this.data.sortMetric,
      sortOrder: this.data.sortMetric ? this.data.sortOrder : ''
    };

    return stockApi
      .list(params)
      .then((res) => {
        const list = (res && res.list) || [];
        const rows = list.map((item) => this.buildRow(item));
        const merged = isFirst ? rows : this.data.rows.concat(rows);
        this.setData({
          rows: merged,
          total: (res && res.total) || merged.length,
          page: targetPage,
          loading: false,
          loadingMore: false,
          hasMore: merged.length < ((res && res.total) || 0)
        });
      })
      .catch(() => {
        this.setData({ loading: false, loadingMore: false });
      });
  },

  buildRow(item) {
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
      cells: metricUtil.buildCells(metrics, this.data.columns, 4)
    };
  },

  onKeywordInput(e) {
    const keyword = e.detail.value;
    this.setData({ keyword: keyword });
    if (this._timer) {
      clearTimeout(this._timer);
    }
    this._timer = setTimeout(() => {
      this.loadPage(1);
    }, 420);
  },

  onKeywordConfirm() {
    this.loadPage(1);
  },

  clearKeyword() {
    this.setData({ keyword: '' });
    this.loadPage(1);
  },

  onTapExchange(e) {
    const code = e.currentTarget.dataset.code || '';
    if (code === this.data.exchange) {
      return;
    }
    this.setData({ exchange: code });
    this.loadPage(1);
  },

  onTapSort(e) {
    const code = e.currentTarget.dataset.code || '';
    let sortMetric = code;
    let sortOrder = 'desc';

    if (this.data.sortMetric === code) {
      if (this.data.sortOrder === 'desc') {
        sortOrder = 'asc';
      } else {
        // 第三次点击取消排序
        sortMetric = '';
        sortOrder = 'desc';
      }
    }

    this.setData({ sortMetric: sortMetric, sortOrder: sortOrder });
    this.loadPage(1);
  },

  goDetail(e) {
    const code = e.currentTarget.dataset.code;
    if (!code) {
      return;
    }
    wx.navigateTo({ url: '/pages/stock/index?code=' + code });
  }
});
