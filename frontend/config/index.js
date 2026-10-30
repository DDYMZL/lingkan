/**
 * 运行时配置
 *
 * 切换环境只需修改 ACTIVE_ENV。
 * 注意：小程序正式版只能请求已在「小程序后台 - 开发管理 - 服务器域名」配置过的 HTTPS 域名；
 *      开发阶段可在开发者工具「详情 - 本地设置」勾选「不校验合法域名」。
 */
const ENVS = {
  dev: {
    baseUrl: 'http://localhost:8080',
    debug: true
  },
  prod: {
    // TODO 上线前替换为正式域名（需 HTTPS 且已在小程序后台备案配置）
    baseUrl: 'https://api.lingkan.cn',
    debug: false
  }
};

const ACTIVE_ENV = 'dev';

module.exports = Object.assign(
  {
    env: ACTIVE_ENV,
    version: '1.0.0',
    /** 后端统一接口前缀 */
    apiPrefix: '/api/v1',
    /** 请求超时（毫秒） */
    timeout: 15000
  },
  ENVS[ACTIVE_ENV]
);
