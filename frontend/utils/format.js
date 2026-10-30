/**
 * 展示层格式化工具
 *
 * 约定：后端返回的 LocalDateTime 可能是 "2026-10-30T14:52:04" 或 "2026-10-30 14:52:04"，
 *      这里统一做兼容处理，避免 iOS 端解析失败。
 */

function pad2(n) {
  return n < 10 ? '0' + n : '' + n;
}

function toDate(value) {
  if (!value && value !== 0) {
    return null;
  }
  if (value instanceof Date) {
    return value;
  }
  const s = String(value).replace('T', ' ').replace(/-/g, '/').replace(/\.\d+$/, '').trim();
  const d = new Date(s);
  return isNaN(d.getTime()) ? null : d;
}

/** 2026-10-30 14:52 */
function formatTime(value, withTime) {
  const d = toDate(value);
  if (!d) {
    return '--';
  }
  const date = d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  if (withTime === false) {
    return date;
  }
  return date + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
}

/** 相对时间：刚刚 / 12 分钟前 / 3 小时前 */
function timeAgo(value) {
  const d = toDate(value);
  if (!d) {
    return '--';
  }
  const diff = Date.now() - d.getTime();
  if (diff <= 0) {
    return '刚刚';
  }
  const min = Math.floor(diff / 60000);
  if (min < 1) {
    return '刚刚';
  }
  if (min < 60) {
    return min + ' 分钟前';
  }
  const hour = Math.floor(min / 60);
  if (hour < 24) {
    return hour + ' 小时前';
  }
  const day = Math.floor(hour / 24);
  if (day < 30) {
    return day + ' 天前';
  }
  return formatTime(value, false);
}

/** A 股习惯：涨红、跌绿、平灰 */
function priceTone(value) {
  const n = Number(value);
  if (!isFinite(n) || n === 0) {
    return 'flat';
  }
  return n > 0 ? 'up' : 'down';
}

/** 带符号的数值文本 */
function signed(value, precision) {
  const n = Number(value);
  if (!isFinite(n)) {
    return '--';
  }
  const p = typeof precision === 'number' ? precision : 2;
  const fixed = n.toFixed(p);
  return n > 0 ? '+' + fixed : fixed;
}

/** 大额数字转 万 / 亿 */
function bigNumber(value) {
  const n = Number(value);
  if (!isFinite(n) || n === 0) {
    return '--';
  }
  const abs = Math.abs(n);
  if (abs >= 1e8) {
    return (n / 1e8).toFixed(2) + '亿';
  }
  if (abs >= 1e4) {
    return (n / 1e4).toFixed(2) + '万';
  }
  return n.toFixed(2);
}

const STATUS_META = {
  OK: { text: '正常', tone: 'ok' },
  MISSING: { text: '缺失', tone: 'muted' },
  STALE: { text: '过期', tone: 'warn' },
  ERROR: { text: '异常', tone: 'danger' },
  PENDING: { text: '待确认', tone: 'info' },
  NOT_APPLICABLE: { text: '不适用', tone: 'muted' },
  RUNNING: { text: '运行中', tone: 'info' },
  SUCCESS: { text: '成功', tone: 'ok' },
  PARTIAL: { text: '部分成功', tone: 'warn' },
  FAILED: { text: '失败', tone: 'danger' },
  HEALTHY: { text: '健康', tone: 'ok' },
  DEGRADED: { text: '降级', tone: 'warn' },
  DOWN: { text: '不可用', tone: 'danger' }
};

function statusMeta(status) {
  return STATUS_META[status] || { text: status || '未知', tone: 'muted' };
}

/** 待确认口径的指标：只显示「待确认」，不展示任何计算结果 */
function isPending(status) {
  return status === 'PENDING';
}

const CATEGORY_NAMES = {
  basic: '基础信息',
  quote: '实时行情',
  valuation: '估值指标',
  dividend: '股息指标',
  financial: '财务指标',
  technical: '技术指标'
};

function categoryName(code) {
  return CATEGORY_NAMES[code] || code || '其他';
}

module.exports = {
  formatTime,
  timeAgo,
  priceTone,
  signed,
  bigNumber,
  statusMeta,
  isPending,
  categoryName,
  toDate
};
