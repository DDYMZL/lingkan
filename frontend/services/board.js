/**
 * 首页自选看板与行情推送。
 * HTTP 始终保留为完整快照兜底，WebSocket 只承担变化行增量更新。
 */
const config = require('../config/index');
const { http } = require('../utils/request');

const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30000;

function getBoard() {
  return http.get('/watchlist/board', null, { toastError: false });
}

function quoteSocketUrl() {
  return config.baseUrl
    .replace(/\/+$/, '')
    .replace(/^http:/, 'ws:')
    .replace(/^https:/, 'wss:') + '/ws/stocks/favorites';
}

/**
 * 建立带指数退避重连的行情流。
 * onOpen(reconnected) 在重连成功时返回 true，页面据此重新拉取完整快照。
 */
function connectQuoteStream(handlers) {
  const callbacks = handlers || {};
  let socket = null;
  let retryTimer = null;
  let attempts = 0;
  let disposed = false;

  function notifyStatus(status, delay) {
    if (callbacks.onStatus) {
      callbacks.onStatus(status, delay);
    }
  }

  function scheduleReconnect() {
    if (disposed || retryTimer) {
      return;
    }
    const delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, attempts), RECONNECT_MAX_MS);
    attempts += 1;
    notifyStatus('RECONNECTING', delay);
    retryTimer = setTimeout(() => {
      retryTimer = null;
      open();
    }, delay);
  }

  function open() {
    if (disposed) {
      return;
    }
    notifyStatus(attempts ? 'RECONNECTING' : 'CONNECTING');
    let task;
    try {
      task = wx.connectSocket({ url: quoteSocketUrl() });
    } catch (err) {
      scheduleReconnect();
      return;
    }
    socket = task;

    task.onOpen(() => {
      if (disposed || socket !== task) {
        return;
      }
      const reconnected = attempts > 0;
      attempts = 0;
      notifyStatus('CONNECTED');
      if (callbacks.onOpen) {
        callbacks.onOpen(reconnected);
      }
    });

    task.onMessage((event) => {
      if (disposed || socket !== task) {
        return;
      }
      try {
        const message = JSON.parse(event.data);
        if (message.type === 'STOCK_QUOTE_UPDATE' && Array.isArray(message.data) && callbacks.onMessage) {
          callbacks.onMessage(message.data);
        }
      } catch (err) {
        // 非约定消息直接忽略，完整快照仍可保证页面可用。
      }
    });

    task.onError((err) => {
      if (disposed || socket !== task) {
        return;
      }
      if (callbacks.onError) {
        callbacks.onError(err);
      }
      try {
        task.close({});
      } catch (closeErr) {
        // 连接已经失效时无需再次处理。
      }
      scheduleReconnect();
    });

    task.onClose(() => {
      if (disposed || socket !== task) {
        return;
      }
      scheduleReconnect();
    });
  }

  open();

  return {
    close() {
      disposed = true;
      if (retryTimer) {
        clearTimeout(retryTimer);
        retryTimer = null;
      }
      const current = socket;
      socket = null;
      if (current) {
        try {
          current.close({});
        } catch (err) {
          // 页面退出时连接可能已关闭。
        }
      }
    }
  };
}

module.exports = {
  getBoard,
  quoteSocketUrl,
  connectQuoteStream
};
