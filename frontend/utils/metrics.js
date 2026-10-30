/**
 * 指标展示组装
 *
 * 后端的指标是「元数据驱动」的：列表字段完全由用户的指标偏好决定，
 * 因此前端不做任何写死列，全部由 preferences + metrics map 动态生成。
 */
const format = require('./format');

/** 基础信息类指标已在股票名称行展示，不再重复占用列表位 */
const BASIC_CODES = ['stock_code', 'stock_name', 'exchange', 'industry', 'listing_date'];

function isBasic(code) {
  return BASIC_CODES.indexOf(code) >= 0;
}

/** 从 metrics（metricCode -> MetricValueDto）中提取行情头部信息 */
function priceOf(metrics) {
  const map = metrics || {};
  const price = map.latest_price;
  const change = map.change_percent;
  const changeValue = change && change.value !== null && change.value !== undefined
    ? Number(change.value)
    : null;

  // 后端 displayValue 不含单位，需要前端拼上 unit（涨跌幅为 %）
  const rawChange = change ? change.displayValue || '--' : '--';
  const changeUnit = change && change.unit ? change.unit : '';
  let changeText = '--';
  if (rawChange !== '--') {
    changeText = (changeValue !== null && changeValue > 0 ? '+' : '') + rawChange + changeUnit;
  }

  return {
    available: !!(price && price.status !== 'MISSING' && price.status !== 'PENDING'),
    price: price ? price.displayValue || '--' : '--',
    changeText: changeText,
    changeValue: changeValue,
    tone: changeValue === null ? 'flat' : format.priceTone(changeValue),
    sourceName: (price && price.sourceName) || '',
    dataTime: (price && price.dataTime) || null
  };
}

/**
 * 组装列表行的指标小格
 * @param {Object} metrics metricCode -> MetricValueDto
 * @param {Array<string>} preferredCodes 用户配置的展示列（已排序）
 * @param {number} limit 最多展示几个
 */
function buildCells(metrics, preferredCodes, limit) {
  const map = metrics || {};
  let codes = preferredCodes && preferredCodes.length
    ? preferredCodes.filter((c) => !isBasic(c))
    : Object.keys(map).filter((c) => !isBasic(c));

  const max = typeof limit === 'number' ? limit : 3;
  const cells = [];

  codes.forEach((code) => {
    if (cells.length >= max) {
      return;
    }
    const m = map[code];
    if (!m) {
      return;
    }
    if (m.displayValue === undefined || m.displayValue === null || m.displayValue === '') {
      return;
    }
    cells.push({
      code: m.metricCode || code,
      label: m.metricName || code,
      value: m.displayValue,
      unit: m.displayValue === '--' ? '' : m.unit || '',
      status: m.status,
      pending: format.isPending(m.status)
    });
  });

  return cells;
}

/** 由指标偏好推导列表展示列（顺序即用户排序） */
function watchlistColumns(preferences) {
  return (preferences || [])
    .filter((p) => p.enabled !== false && p.showInWatchlist && !isBasic(p.metricCode))
    .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    .map((p) => p.metricCode);
}

/**
 * 由 /metrics/categories 的返回结果建立 metricCode -> {category, categoryName} 索引
 * @param {Array} groups [{category, categoryName, metrics: [{metricCode, ...}]}]
 */
function buildCategoryIndex(groups) {
  const index = {};
  (groups || []).forEach((group) => {
    (group.metrics || []).forEach((m) => {
      index[m.metricCode] = {
        category: group.category,
        categoryName: group.categoryName || format.categoryName(group.category),
        sortOrder: m.sortOrder || 0,
        description: m.description || '',
        calculationStatus: m.calculationStatus || '',
        unit: m.unit || '',
        defaultPrecision: m.defaultPrecision
      };
    });
  });
  return index;
}

/**
 * 把指标取值按分类聚合，用于个股详情页
 * @param {Array} values MetricValueDto 列表
 * @param {Object} categoryIndex buildCategoryIndex 的结果
 */
function groupByCategory(values, categoryIndex) {
  const idx = categoryIndex || {};
  const groups = {};
  const order = [];

  (values || []).forEach((v) => {
    const meta = idx[v.metricCode] || {};
    const category = meta.category || 'other';
    if (!groups[category]) {
      groups[category] = {
        category,
        categoryName: meta.categoryName || format.categoryName(category),
        items: [],
        sortOrder: 999
      };
      order.push(category);
    }
    const statusInfo = format.statusMeta(v.status);
    const pending = format.isPending(v.status);
    groups[category].items.push({
      code: v.metricCode,
      name: v.metricName,
      rawValue: v.value,
      displayValue: pending ? '待确认' : v.displayValue || '--',
      unit: pending || v.displayValue === '--' ? '' : v.unit || '',
      status: v.status,
      statusText: v.statusText || statusInfo.text,
      statusTone: statusInfo.tone,
      pending,
      abnormal: v.status !== 'OK',
      sourceCode: v.sourceCode || '',
      sourceName: v.sourceName || '',
      dataTime: v.dataTime || '',
      dataTimeText: format.formatTime(v.dataTime),
      fetchTime: v.fetchTime || '',
      calculationVersion: v.calculationVersion || '',
      description: meta.description || ''
    });
    const so = meta.sortOrder;
    if (typeof so === 'number' && so < groups[category].sortOrder) {
      groups[category].sortOrder = so;
    }
  });

  return order
    .map((key) => groups[key])
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((g) => {
      g.items.sort((a, b) => {
        const sa = (idx[a.code] && idx[a.code].sortOrder) || 0;
        const sb = (idx[b.code] && idx[b.code].sortOrder) || 0;
        return sa - sb;
      });
      return g;
    });
}

/** 详情页头部核心字段（固定展示，但也带来源，避免"看着像实时"的误导） */
function quoteBoard(quote) {
  if (!quote || quote.available === false) {
    return null;
  }
  return {
    latestPrice: quote.latestPrice != null ? Number(quote.latestPrice).toFixed(2) : '--',
    changePercent: quote.changePercent != null ? Number(quote.changePercent) : null,
    changePercentText: quote.changePercent != null ? format.signed(quote.changePercent, 2) + '%' : '--',
    changeAmountText: quote.changeAmount != null ? format.signed(quote.changeAmount, 2) : '--',
    tone: quote.changePercent != null ? format.priceTone(quote.changePercent) : 'flat',
    high: quote.highPrice != null ? Number(quote.highPrice).toFixed(2) : '--',
    low: quote.lowPrice != null ? Number(quote.lowPrice).toFixed(2) : '--',
    open: quote.openPrice != null ? Number(quote.openPrice).toFixed(2) : '--',
    preClose: quote.preClose != null ? Number(quote.preClose).toFixed(2) : '--',
    turnover: quote.turnoverRate != null ? Number(quote.turnoverRate).toFixed(2) + '%' : '--',
    amount: format.bigNumber(quote.amount),
    volume: format.bigNumber(quote.volume),
    marketCap: format.bigNumber(quote.totalMarketCap),
    sourceName: quote.sourceName || '',
    dataTimeText: format.formatTime(quote.dataTime),
    fetchTimeText: format.formatTime(quote.fetchTime)
  };
}

module.exports = {
  BASIC_CODES,
  isBasic,
  priceOf,
  buildCells,
  watchlistColumns,
  buildCategoryIndex,
  groupByCategory,
  quoteBoard
};
