/**
 * 首页自选看板：HTTP 完整快照 + WebSocket 变化行更新。
 * 页面只负责展示状态，指标数值全部由后端计算引擎返回。
 *
 * v1.2（spec 010 §7.1）：十列横向滚动表格 + 首列固定。表头与表体共用 COLUMNS
 * 一份列定义，保证横向滚动时列宽与顺序对齐；行情推送只重写变化行的 cells 数组。
 */
const boardApi = require('../../services/board');
const watchlistApi = require('../../services/watchlist');
const format = require('../../utils/format');
const nav = require('../../utils/nav');

/** 目标价档位与后端 targetPrices 的键一致（spec 010 v1.2：六档 4.0%~6.5%）。 */
const TARGET_YIELDS = ['4.0', '4.5', '5.0', '5.5', '6.0', '6.5'];

/**
 * 首列之外的九个数值列（顺序即展示顺序，spec 010 FR-10.1）。
 * cls 对应 WXSS 里的列宽类，表头与表体同源，改列宽只改 WXSS 一处。
 */
const COLUMNS = [
  { key: 'price', label: '现价', cls: 'cell--price' },
  { key: 'change', label: '涨跌幅', cls: 'cell--change' },
  { key: 'yield', label: '股息率TTM', cls: 'cell--yield' },
  { key: 'pe', label: 'PE TTM', cls: 'cell--pe' },
  { key: 'ma60w', label: '60周', cls: 'cell--week' },
  { key: 'ma120w', label: '120周', cls: 'cell--week' },
  { key: 'weekUpper', label: '周上', cls: 'cell--week' },
  { key: 'weekMiddle', label: '周中', cls: 'cell--week' },
  { key: 'weekLower', label: '周下', cls: 'cell--week' }
];

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
      label: Number(key) + '%',
      value: hasNumber(value) ? Number(value) : null,
      text: priceText(value),
      reached: targetReached(item.price, value)
    };
  });
}

/**
 * 九个数值列的单元格；顺序与 COLUMNS 一致。
 * 周线五列（60周/120周/周上/周中/周下）是价位参考线，不做涨跌着色；
 * 根数不足或数据异常时后端返回 null，这里统一渲染 --，不用 0 冒充（铁律 1）。
 */
function buildCells(item) {
  const tone = format.priceTone(item.changePercent);
  const values = {
    price: { text: priceText(item.price), tone: tone },
    change: { text: changeText(item.changePercent), tone: tone },
    yield: { text: yieldText(item.dividendYieldTtm), emphasis: true },
    pe: { text: peText(item), tone: item.peStatus === 'LOSS' ? 'down' : '' },
    ma60w: { text: priceText(item.ma60w) },
    ma120w: { text: priceText(item.ma120w) },
    weekUpper: { text: priceText(item.weekUpper) },
    weekMiddle: { text: priceText(item.weekMiddle) },
    weekLower: { text: priceText(item.weekLower) }
  };
  return COLUMNS.map((column) => {
    const cell = values[column.key] || { text: '--' };
    return {
      key: column.key,
      cls: column.cls,
      text: cell.text,
      tone: cell.tone || '',
      emphasis: !!cell.emphasis
    };
  });
}

function statusText(dataStatus) {
  if (dataStatus === 'STALE') {
    return '行情过期';
  }
  return dataStatus === 'SUSPENDED' ? '停牌' : '';
}

function buildRow(item) {
  return {
    code: item.stockCode,
    name: item.stockName || item.stockCode,
    exchange: item.exchange || '',
    // 行业缺失显示 --，不由前端推断或补「其他」（spec 010 FR-10.12）
    industryText: item.industry || '--',
    cells: buildCells(item),
    targets: buildTargets(item),
    statusText: statusText(item.dataStatus)
  };
}

Page({
  data: {
    loading: true,
    loadFailed: false,
    columns: COLUMNS,
    rows: [],
    count: 0,
    expandedMap: {},
    quoteTimeRaw: '',
    quoteTimeText: '--',
    connectionText: '连接中',
    connectionTone: 'muted',
    refreshingQuotes: false
  },

  onLoad() {
    this._active = true;
    this._hasShown = false;
    this._rowIndexes = {};
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
        const rows = sourceRows.map(buildRow);
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

  /**
   * 只写变化行的路径，不用整份 rows 替换触发全表渲染。
   * 周线五列随推送下发：本周未收盘周线的收盘价用实时价覆盖（spec 007 Q7-8）。
   */
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
      patch[prefix + '.cells'] = buildCells(update);
      patch[prefix + '.statusText'] = statusText(update.dataStatus);

      // 目标价不随行情推送（只随分红变化），这里只重算「是否已达标」
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

  toggleTargets(e) {
    const code = e.currentTarget.dataset.code;
    if (!code) {
      return;
    }
    const patch = {};
    patch['expandedMap.' + code] = !this.data.expandedMap[code];
    this.setData(patch);
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
    nav.goStockDetail(e.currentTarget.dataset.code, e.currentTarget.dataset.name);
  },

  goSearch() {
    wx.navigateTo({ url: '/pages/search/index' });
  }
});
