/**
 * 统一请求封装
 *
 * - 自动拼接 baseUrl + /api/v1
 * - 自动携带 X-LingKan-User
 * - 统一解析后端 ApiResponse{code,message,data}，code === 0 视为成功
 * - 统一错误提示，页面可选择自行处理（toastError: false）
 */
const config = require('../config/index');
const { getUserId } = require('./user');

function cleanParams(data) {
  if (!data || typeof data !== 'object') {
    return data;
  }
  const out = {};
  Object.keys(data).forEach((key) => {
    const v = data[key];
    if (v === undefined || v === null || v === '') {
      return;
    }
    out[key] = v;
  });
  return out;
}

function networkHint(err) {
  const raw = (err && (err.errMsg || err.message)) || '';
  if (raw.indexOf('timeout') >= 0) {
    return '请求超时，请稍后重试';
  }
  if (raw.indexOf('not in domain list') >= 0 || raw.indexOf('domain') >= 0) {
    return '域名未配置：开发阶段请在开发者工具勾选「不校验合法域名」';
  }
  if (raw.indexOf('fail') >= 0) {
    return '无法连接服务端，请确认后端已启动';
  }
  return raw || '网络异常';
}

function request(options) {
  const opt = options || {};
  const method = opt.method || 'GET';
  const showLoading = !!opt.loading;

  if (showLoading) {
    wx.showLoading({ title: opt.loadingText || '加载中', mask: true });
  }

  const task = new Promise((resolve, reject) => {
    wx.request({
      url: config.baseUrl + config.apiPrefix + opt.url,
      method: method,
      data: cleanParams(opt.data),
      timeout: opt.timeout || config.timeout,
      header: {
        'content-type': 'application/json',
        'X-LingKan-User': getUserId()
      },
      success(res) {
        const body = res.data;
        if (res.statusCode >= 200 && res.statusCode < 300 && body && body.code === 0) {
          resolve(body.data);
          return;
        }
        const msg = (body && body.message) || '请求失败（HTTP ' + res.statusCode + '）';
        const error = new Error(msg);
        error.statusCode = res.statusCode;
        error.response = body;
        reject(error);
      },
      fail(err) {
        reject(new Error(networkHint(err)));
      },
      complete() {
        if (showLoading) {
          wx.hideLoading();
        }
      }
    });
  });

  return task.catch((err) => {
    if (opt.toastError !== false) {
      wx.showToast({ title: err.message || '网络异常', icon: 'none', duration: 2400 });
    }
    throw err;
  });
}

const http = {
  get(url, data, extra) {
    return request(Object.assign({ url, data, method: 'GET' }, extra));
  },
  post(url, data, extra) {
    return request(Object.assign({ url, data, method: 'POST' }, extra));
  },
  put(url, data, extra) {
    return request(Object.assign({ url, data, method: 'PUT' }, extra));
  },
  del(url, data, extra) {
    return request(Object.assign({ url, data, method: 'DELETE' }, extra));
  }
};

module.exports = {
  request,
  http
};
