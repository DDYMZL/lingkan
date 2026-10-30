/**
 * 本地用户标识
 *
 * 第一版不做注册登录，用小程序本地生成的稳定标识作为 user_id，
 * 通过请求头 X-LingKan-User 传给后端（后端 UserContext 读取）。
 */
const STORAGE_KEY = 'lingkan_user_id';

function ensureUserId() {
  let id = '';
  try {
    id = wx.getStorageSync(STORAGE_KEY) || '';
  } catch (e) {
    id = '';
  }
  if (!id) {
    id = 'mini-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    try {
      wx.setStorageSync(STORAGE_KEY, id);
    } catch (e) {
      // ignore
    }
  }
  return id;
}

function getUserId() {
  return ensureUserId();
}

module.exports = {
  ensureUserId,
  getUserId
};
