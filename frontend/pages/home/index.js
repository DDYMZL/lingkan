/**
 * 首页自选看板：HTTP 完整快照 + WebSocket 变化行更新。
 * 页面只负责展示状态，指标数值全部由后端计算引擎返回。
 */
const boardApi = require('../../services/board');
const watchlistApi = require('../../services/watchlist');
const format = require('../../utils/format');

const TARGET_YIELDS = ['4.0', '4.5', '5.0', '5.5', '6.0', '6.5', '7.0', '7.5', '8.0'];
const TARGET_COLUMNS = TARGET_YIELDS.map((value) => ({
  key: value,
  label: Number(value) + '%'
}));

function hasNumber(value) {
  return value !== null && value !== undefined && value !== '' && isFinite(Number(value));
}

function fixed(value, precision) {
  return hasNumber(value) ? Number(value).toFixed(precision) : '--';
}

function priceText(value) {
  return fixed(value, 2);
}

function changeText(value) {
  if (!hasNumber(value)) {
    return '--';
  }
  return format.signed(value, 2) + '%';
}

function yieldText(value) {
  return hasNumber(value) ? Number(value).toFixed(2) + '%' : '--';
}

function peText(item) {
  if (item.peStatus === 'LOSS') {
    return '亏损';
  }
  return fixed(item.peTtm, 2);
}

function targetReached(price, targetPrice) {
  return hasNumber(price) && hasNumber(targetPrice) && Number(targetPrice) > 0
    && Number(price) <= Number(targetPrice);
}

function buildTargets(item) {
  const targets = item.targetPrices || {};
  return TARGET_YIELDS.map((key) => {
    const value = targets[key];
    return {
      key: key,
      value: hasNumber(value) ? Number(value) : null,
      text: priceText(value),
      reached: targetReached(item.price, value)
    };
  });
}

Page({
  data: {
    loading: true,
    loadFailed: false,
    rows: [],
    count: 0,
    targetColumns: TARGET_COLUMNS,
    quoteTimeRaw: '',
    quoteTimeText: '--',
    connectionText: '连接中',
    connectionTone: 'muted',
    refreshingQuotes: false,
    headerScrollLeft: 0
  },

  onLoad() {
    this._active = true;
    this._hasShown = false;
    this._rowIndexes = {};
    this._headerScrollLeft = 0;
    this.loadBoard();
    this.openQuoteStream();
  },

  onShow() {
    this._active = true;
    if (this._hasShown) {
      this.loadBoard({ silent: true });
      this.openQuoteStream();
    }
    this._hasShown = true;
  },

  onHide() {
    this._active = false;
    this.closeQuoteStream();
  },

  onUnload() {
    this._active = false;
    this.closeQuoteStream();
  },

  onPullDownRefresh() {
    const refresh = watchlistApi.refresh().catch(() => null);
    Promise.all([this.loadBoard({ silent: true }), refresh]).then(() => {
      wx.stopPullDownRefresh();
    });
  },

  loadBoard(options) {
    const silent = !!(options && options.silent);
    if (!this.data.rows.length) {
      this.setData({ loading: true });
    }
    return boardApi.getBoard()
      .then((res) => {
        const sourceRows = (res && res.rows) || [];
        const rows = sourceRows.map((item) => ({
          code: item.stockCode,
          name: item.stockName || item.stockCode,
          exchange: item.exchange || '',
          priceValue: hasNumber(item.price) ? Number(item.price) : null,
          priceText: priceText(item.price),
          changeText: changeText(item.changePercent),
          tone: format.priceTone(item.changePercent),
          yieldText: yieldText(item.dividendYieldTtm),
          peText: peText(item),
          peLoss: item.peStatus === 'LOSS',
          targets: buildTargets(item),
          quoteTime: item.quoteTime || '',
          statusText: item.dataStatus === 'STALE'
            ? '行情过期'
            : (item.dataStatus === 'SUSPENDED' ? '停牌' : '')
        }));
        const indexes = {};
        rows.forEach((row, index) => {
          indexes[row.code] = index;
        });
        this._rowIndexes = indexes;
        const quoteTime = (res && res.quoteTime) || '';
        this.setData({
          loading: false,
          loadFailed: false,
          rows: rows,
          count: (res && typeof res.count === 'number') ? res.count : rows.length,
          quoteTimeRaw: quoteTime,
          quoteTimeText: quoteTime ? format.formatTime(quoteTime) : '--'
        });
      })
      .catch((err) => {
        this.setData({
          loading: false,
          loadFailed: !this.data.rows.length
        });
        if (!silent) {
          wx.showToast({ title: err.message || '看板加载失败', icon: 'none' });
        }
      });
  },

  openQuoteStream() {
    if (!this._active || this._quoteStream) {
      return;
    }
    this._quoteStream = boardApi.connectQuoteStream({
      onOpen: (reconnected) => {
        if (!this._active) {
          return;
        }
        this.setData({ connectionText: '实时已连接', connectionTone: 'ok' });
        if (reconnected) {
          this.loadBoard({ silent: true });
        }
      },
      onMessage: (updates) => this.applyQuoteUpdates(updates),
      onStatus: (status) => {
        if (!this._active) {
          return;
        }
        if (status === 'CONNECTED') {
          return;
        }
        this.setData({
          connectionText: status === 'RECONNECTING' ? '断线重连中' : '正在连接',
          connectionTone: status === 'RECONNECTING' ? 'warn' : 'muted'
        });
      }
    });
  },

  closeQuoteStream() {
    if (this._quoteStream) {
      this._quoteStream.close();
      this._quoteStream = null;
    }
  },

  /** 只写变化行的路径，不用整份 rows 替换触发全表渲染。 */
  applyQuoteUpdates(updates) {
    const patch = {};
    let latestTime = this.data.quoteTimeRaw;
    (updates || []).forEach((update) => {
      const index = this._rowIndexes[update.stockCode];
      if (index === undefined) {
        return;
      }
      const row = this.data.rows[index];
      const prefix = 'rows[' + index + ']';
      patch[prefix + '.priceValue'] = hasNumber(update.price) ? Number(update.price) : null;
      patch[prefix + '.priceText'] = priceText(update.price);
      patch[prefix + '.changeText'] = changeText(update.changePercent);
      patch[prefix + '.tone'] = format.priceTone(update.changePercent);
      patch[prefix + '.yieldText'] = yieldText(update.dividendYieldTtm);
      patch[prefix + '.peText'] = peText(update);
      patch[prefix + '.peLoss'] = update.peStatus === 'LOSS';
      patch[prefix + '.quoteTime'] = update.quoteTime || '';
      patch[prefix + '.statusText'] = update.dataStatus === 'STALE'
        ? '行情过期'
        : (update.dataStatus === 'SUSPENDED' ? '停牌' : '');

      row.targets.forEach((target, targetIndex) => {
        const reached = targetReached(update.price, target.value);
        if (reached !== target.reached) {
          patch[prefix + '.targets[' + targetIndex + '].reached'] = reached;
        }
      });

      if (update.quoteTime && format.toDate(update.quoteTime)
        && (!latestTime || format.toDate(update.quoteTime) > format.toDate(latestTime))) {
        latestTime = update.quoteTime;
      }
    });
    if (latestTime !== this.data.quoteTimeRaw) {
      patch.quoteTimeRaw = latestTime;
      patch.quoteTimeText = format.formatTime(latestTime);
    }
    if (Object.keys(patch).length) {
      this.setData(patch);
    }
  },

  onBoardScroll(e) {
    const left = e.detail.scrollLeft || 0;
    if (Math.abs(left - this._headerScrollLeft) < 1) {
      return;
    }
    this._headerScrollLeft = left;
    this.setData({ headerScrollLeft: left });
  },

  onRefreshQuotes() {
    if (this.data.refreshingQuotes) {
      return;
    }
    this.setData({ refreshingQuotes: true });
    watchlistApi.refresh()
      .then((res) => {
        wx.showToast({
          title: '已提交刷新（' + ((res && res.count) || 0) + ' 只）',
          icon: 'none'
        });
        return this.loadBoard({ silent: true });
      })
      .catch((err) => {
        wx.showToast({ title: err.message || '刷新提交失败', icon: 'none' });
      })
      .then(() => this.setData({ refreshingQuotes: false }));
  },

  retryLoad() {
    this.loadBoard();
  },

  goDetail(e) {
    const code = e.currentTarget.dataset.code;
    if (code) {
      wx.navigateTo({ url: '/pages/stock/index?code=' + code });
    }
  },

  goSearch() {
    wx.navigateTo({ url: '/pages/search/index' });
  }
});
