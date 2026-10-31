/**
 * 页面跳转工具。
 *
 * <p>个股详情的导航栏标题依赖股票名称：名称随路由同步下发，详情页 onLoad 就能
 * 设置标题；若只靠详情接口返回后再设置，用户秒退时异步回调会在页面出栈后才写
 * 导航栏，把个股名污染到栈顶的其它页面（实测会覆盖首页标题）。
 */

/** 跳转个股详情，name 可选（缺失时详情页回退为异步取名称）。 */
function goStockDetail(code, name) {
  if (!code) {
    return;
  }
  let url = '/pages/stock/index?code=' + encodeURIComponent(code);
  if (name) {
    url += '&name=' + encodeURIComponent(name);
  }
  wx.navigateTo({ url: url });
}

module.exports = {
  goStockDetail
};
