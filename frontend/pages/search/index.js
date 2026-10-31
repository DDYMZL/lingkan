/**
 * 股票搜索：支持代码 / 名称，结果可直接加入自选
 */
const stockApi = require('../../services/stock');
const watchlistApi = require('../../services/watchlist');
const metricUtil = require('../../utils/metrics');
const nav = require('../../utils/nav');

Page({
  data: {
    keyword: '',
    results: [],
    searching: false,
    searched: false,
    watchMap: {},
    focus: true
  },

  onLoad() {
    this.loadWatchlist();
  },

  onUnload() {
    if (this._timer) {
      clearTimeout(this._timer);
      this._timer = null;
    }
  },

  /** 已加自选的股票直接标记，避免重复添加 */
  loadWatchlist() {
    return watchlistApi
      .list(1, 100)
      .catch(() => ({ list: [] }))
      .then((res) => {
        const map = {};
        ((res && res.list) || []).forEach((item) => {
          map[item.stockCode] = true;
        });
        this.setData({ watchMap: map });
      });
  },

  onInput(e) {
    const keyword = e.detail.value;
    this.setData({ keyword: keyword });

    if (this._timer) {
      clearTimeout(this._timer);
    }
    if (!keyword.trim()) {
      this.setData({ results: [], searched: false });
      return;
    }
    this._timer = setTimeout(() => this.doSearch(), 380);
  },

  onConfirm() {
    this.doSearch();
  },

  doSearch() {
    const keyword = (this.data.keyword || '').trim();
    if (!keyword) {
      return;
    }
    this.setData({ searching: true });
    stockApi
      .search(keyword, 30)
      .then((list) => {
        const results = (list || []).map((item) => {
          const quote = metricUtil.priceOf(item.metrics);
          return {
            code: item.stockCode,
            name: item.stockName,
            exchange: item.exchange || '',
            industry: item.industry || '',
            st: !!item.st,
            price: quote.price,
            changeText: quote.changeText,
            tone: quote.tone
          };
        });
        this.setData({ results: results, searching: false, searched: true });
      })
      .catch(() => {
        this.setData({ searching: false, searched: true });
      });
  },

  onAdd(e) {
    const code = e.currentTarget.dataset.code;
    if (!code || this.data.watchMap[code]) {
      return;
    }
    watchlistApi
      .add(code, '')
      .then(() => {
        const map = Object.assign({}, this.data.watchMap);
        map[code] = true;
        this.setData({ watchMap: map });
        wx.showToast({ title: '已加入自选', icon: 'success' });
      })
      .catch(() => {});
  },

  goDetail(e) {
    nav.goStockDetail(e.currentTarget.dataset.code, e.currentTarget.dataset.name);
  }
});
