/**
 * 个股详情
 *
 * 结构：基础信息 → 实时行情 → 指标（按分类分组，每个指标都标注来源与数据时间）
 *      → 历史走势 → 分红记录
 * 每个指标可点开「多数据源对比」，这是本项目"数据可追溯"的核心入口。
 */
const stockApi = require('../../services/stock');
const metricsApi = require('../../services/metrics');
const preferenceApi = require('../../services/preference');
const watchlistApi = require('../../services/watchlist');
const metricUtil = require('../../utils/metrics');

Page({
  data: {
    code: '',
    loading: true,
    failed: false,
    basic: null,
    board: null,
    groups: [],
    chartOptions: [],
    chartMetric: '',
    chartUnit: '',
    chartName: '',
    chartLoading: false,
    range: '1Y',
    ranges: [
      { code: '1M', name: '1月' },
      { code: '3M', name: '3月' },
      { code: '6M', name: '6月' },
      { code: '1Y', name: '1年' },
      { code: '3Y', name: '3年' }
    ],
    points: [],
    dividends: [],
    inWatchlist: false,
    watchBusy: false,
    refreshing: false
  },

  onLoad(options) {
    const code = (options && options.code) || '';
    if (!code) {
      this.setData({ loading: false, failed: true });
      wx.showToast({ title: '缺少股票代码', icon: 'none' });
      return;
    }
    this.setData({ code: code });
    this.loadAll();
  },

  onPullDownRefresh() {
    this.loadAll(true);
  },

  /** 失败重试（empty-state 触发时无参数传参） */
  reload() {
    this.loadAll();
  },

  onShareAppMessage() {
    const basic = this.data.basic;
    return {
      title: basic ? basic.name + ' ' + basic.code + ' · 玲看股息' : '玲看股息',
      path: '/pages/stock/index?code=' + this.data.code
    };
  },

  loadAll(isPull) {
    if (!isPull) {
      this.setData({ loading: true, failed: false });
    }
    const code = this.data.code;

    return Promise.all([
      stockApi.detail(code),
      stockApi.dividends(code).catch(() => []),
      metricsApi.categories().catch(() => []),
      preferenceApi.list().catch(() => []),
      watchlistApi.list(1, 100).catch(() => ({ list: [] }))
    ])
      .then((res) => {
        const detail = res[0] || {};
        const dividends = res[1] || [];
        const categories = res[2] || [];
        const watchPage = res[4] || { list: [] };

        const stock = detail.stock || null;
        const catIndex = metricUtil.buildCategoryIndex(categories);
        const groups = metricUtil
          .groupByCategory(detail.metrics || [], catIndex)
          .filter((g) => g.category !== 'basic');

        const board = metricUtil.quoteBoard(detail.quote);

        // 可选做历史曲线的指标：有数值且非待确认口径
        const options = [];
        groups.forEach((g) => {
          g.items.forEach((it) => {
            if (typeof it.rawValue === 'number' && !it.pending) {
              options.push({ code: it.code, name: it.name, unit: it.unit });
            }
          });
        });
        const chartOptions = options.slice(0, 8);

        const inWatchlist = ((watchPage.list || []) || []).some((s) => s.stockCode === code);

        if (stock) {
          wx.setNavigationBarTitle({ title: stock.stockName });
        }

        this.setData({
          loading: false,
          failed: false,
          basic: stock
            ? {
                name: stock.stockName,
                code: stock.stockCode,
                exchange: stock.exchange || '',
                board: stock.board || '',
                industry: stock.industry || '',
                listingDate: stock.listingDate || '',
                st: !!stock.st
              }
            : null,
          board: board,
          groups: groups,
          chartOptions: chartOptions,
          inWatchlist: inWatchlist,
          dividends: dividends.map((d) => ({
            fiscalYear: d.fiscalYear || '--',
            cash: d.cashDividendPerShare != null ? Number(d.cashDividendPerShare).toFixed(4) : '--',
            exDate: d.exDividendDate || '',
            recordDate: d.recordDate || '',
            planStatus: d.planStatus || '',
            sourceCode: d.sourceCode || ''
          }))
        });

        if (chartOptions.length) {
          const keep = chartOptions.some((o) => o.code === this.data.chartMetric);
          const target = keep ? this.data.chartMetric : chartOptions[0].code;
          this.loadHistory(target, this.data.range);
        }

        wx.stopPullDownRefresh();
      })
      .catch(() => {
        this.setData({ loading: false, failed: true });
        wx.stopPullDownRefresh();
      });
  },

  loadHistory(metricCode, range) {
    if (!metricCode) {
      return Promise.resolve();
    }
    const option = this.data.chartOptions.filter((o) => o.code === metricCode)[0] || {};
    this.setData({
      chartMetric: metricCode,
      chartName: option.name || '',
      chartUnit: option.unit || '',
      chartLoading: true
    });

    return stockApi
      .history(this.data.code, metricCode, range || this.data.range)
      .then((series) => {
        const points = (series && series.points) || [];
        this.setData({
          points: points.map((p) => ({ date: String(p.date || ''), value: p.value })),
          chartLoading: false
        });
      })
      .catch(() => {
        this.setData({ points: [], chartLoading: false });
      });
  },

  onTapChartMetric(e) {
    const code = e.currentTarget.dataset.code;
    if (!code || code === this.data.chartMetric) {
      return;
    }
    this.loadHistory(code, this.data.range);
  },

  onTapRange(e) {
    const range = e.currentTarget.dataset.code;
    if (!range || range === this.data.range) {
      return;
    }
    this.setData({ range: range });
    this.loadHistory(this.data.chartMetric, range);
  },

  toggleWatch() {
    if (this.data.watchBusy) {
      return;
    }
    const code = this.data.code;
    const inList = this.data.inWatchlist;
    this.setData({ watchBusy: true });

    const action = inList ? watchlistApi.remove(code) : watchlistApi.add(code, '');
    action
      .then(() => {
        this.setData({ inWatchlist: !inList, watchBusy: false });
        wx.showToast({ title: inList ? '已移出自选' : '已加入自选', icon: 'success' });
      })
      .catch(() => {
        this.setData({ watchBusy: false });
      });
  },

  onRefresh() {
    if (this.data.refreshing) {
      return;
    }
    this.setData({ refreshing: true });
    stockApi
      .refresh(this.data.code)
      .then(() => {
        wx.showToast({ title: '已提交刷新', icon: 'none' });
        setTimeout(() => {
          this.setData({ refreshing: false });
          this.loadAll(true);
        }, 1600);
      })
      .catch(() => {
        this.setData({ refreshing: false });
      });
  },

  goCompare(e) {
    const dataset = e.currentTarget.dataset;
    const metricCode = dataset.code;
    const name = dataset.name || '';
    if (!metricCode) {
      return;
    }
    wx.navigateTo({
      url:
        '/pages/compare/index?code=' +
        this.data.code +
        '&metricCode=' +
        metricCode +
        '&name=' +
        encodeURIComponent(name)
    });
  }
});
