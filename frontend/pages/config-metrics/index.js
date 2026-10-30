/**
 * 指标展示配置
 *
 * 全部渲染逻辑都由「指标定义 + 用户偏好」驱动，页面不写死任何指标。
 * 可配置项：是否启用 / 在哪些页面展示 / 显示精度 / 排序 / 主数据源。
 */
const metricsApi = require('../../services/metrics');
const preferenceApi = require('../../services/preference');
const dataSourceApi = require('../../services/datasource');

const app = getApp();
const FALLBACK_SOURCE_LABEL = '跟随系统默认';

Page({
  data: {
    loading: true,
    rows: [],
    dirty: false
  },

  onLoad() {
    this.load();
  },

  onUnload() {
    if (this.data.dirty) {
      app.globalData.preferenceDirty = true;
    }
  },

  load() {
    this.setData({ loading: true });

    return Promise.all([
      metricsApi.categories().catch(() => []),
      preferenceApi.list().catch(() => []),
      dataSourceApi.bindings().catch(() => []),
      dataSourceApi.list().catch(() => [])
    ])
      .then((res) => {
        const categories = res[0] || [];
        const prefs = res[1] || [];
        const bindings = res[2] || [];
        const providers = res[3] || [];

        const prefMap = {};
        prefs.forEach((p) => {
          prefMap[p.metricCode] = p;
        });

        const providerName = {};
        providers.forEach((p) => {
          providerName[p.providerCode] = p.providerName;
        });

        const bindMap = {};
        bindings.forEach((b) => {
          if (!bindMap[b.metricCode]) {
            bindMap[b.metricCode] = [];
          }
          bindMap[b.metricCode].push({
            code: b.providerCode,
            name: providerName[b.providerCode] || b.providerCode
          });
        });

        const rows = [];
        categories.forEach((group, gi) => {
          (group.metrics || []).forEach((def) => {
            const pref = prefMap[def.metricCode] || {};
            const options = bindMap[def.metricCode] || [];
            const sourceCodes = [''].concat(options.map((o) => o.code));
            const sourceNames = [FALLBACK_SOURCE_LABEL].concat(options.map((o) => o.name));
            const selected = pref.selectedSource || '';
            const sourceIndex = Math.max(sourceCodes.indexOf(selected), 0);

            rows.push({
              metricCode: def.metricCode,
              name: pref.displayName || def.metricName,
              category: group.category,
              categoryName: group.categoryName || def.category,
              catOrder: gi,
              sortOrder: pref.sortOrder != null ? pref.sortOrder : def.sortOrder || 0,
              enabled: pref.enabled != null ? !!pref.enabled : !!def.enabled,
              showInHome: !!pref.showInHome,
              showInWatchlist: !!pref.showInWatchlist,
              showInDetail: pref.showInDetail != null ? !!pref.showInDetail : !!def.enabled,
              precision: pref.precision != null ? pref.precision : def.defaultPrecision || 2,
              selectedSource: selected,
              unit: pref.unit || def.unit || '',
              // 以下三项本页不提供编辑，但保存时必须原样带回，否则会被后端重置为 false
              filterable: !!pref.filterable,
              sortable: pref.sortable != null ? !!pref.sortable : true,
              recordHistory: !!pref.recordHistory,
              pending: def.calculationStatus === 'PENDING',
              description: def.description || '',
              calculationStatus: def.calculationStatus || '',
              expanded: false,
              sourceCodes: sourceCodes,
              sourceNames: sourceNames,
              sourceIndex: sourceIndex,
              showHeader: false
            });
          });
        });

        rows.sort(
          (a, b) => a.catOrder - b.catOrder || a.sortOrder - b.sortOrder
        );

        this.setData({
          loading: false,
          rows: this.markHeaders(this.reindex(rows)),
          dirty: false
        });
      })
      .catch(() => {
        this.setData({ loading: false });
      });
  },

  /** 同分类内按顺序重排 sortOrder，跨分类顺序由 catOrder 决定 */
  reindex(rows) {
    const counters = {};
    rows.forEach((r) => {
      counters[r.category] = (counters[r.category] || 0) + 10;
      r.sortOrder = counters[r.category];
    });
    return rows;
  },

  markHeaders(rows) {
    let lastCategory = '';
    rows.forEach((r) => {
      r.showHeader = r.category !== lastCategory;
      lastCategory = r.category;
    });
    return rows;
  },

  noop() {},

  onToggleExpand(e) {
    const index = Number(e.currentTarget.dataset.index);
    const row = this.data.rows[index];
    if (!row) {
      return;
    }
    const next = !row.expanded;
    this.setData({
      ['rows[' + index + '].expanded']: next
    });
  },

  onToggleEnabled(e) {
    const index = Number(e.currentTarget.dataset.index);
    const value = e.detail.value;
    this.setData({
      ['rows[' + index + '].enabled']: value,
      dirty: true
    });
  },

  onTogglePosition(e) {
    const dataset = e.currentTarget.dataset;
    const index = Number(dataset.index);
    const field = dataset.field;
    const row = this.data.rows[index];
    if (!row || !field) {
      return;
    }
    this.setData({
      ['rows[' + index + '].' + field]: !row[field],
      dirty: true
    });
  },

  onPrecision(e) {
    const dataset = e.currentTarget.dataset;
    const index = Number(dataset.index);
    const delta = Number(dataset.delta);
    const row = this.data.rows[index];
    if (!row) {
      return;
    }
    const next = Math.min(6, Math.max(0, (row.precision || 0) + delta));
    if (next === row.precision) {
      return;
    }
    this.setData({
      ['rows[' + index + '].precision']: next,
      dirty: true
    });
  },

  onMove(e) {
    const dataset = e.currentTarget.dataset;
    const index = Number(dataset.index);
    const dir = dataset.dir === 'up' ? -1 : 1;
    const rows = this.data.rows.slice();
    const current = rows[index];
    if (!current) {
      return;
    }

    let swapIndex = -1;
    for (let i = index + dir; i >= 0 && i < rows.length; i += dir) {
      if (rows[i].category === current.category) {
        swapIndex = i;
        break;
      }
      if (dir < 0 && rows[i].category !== current.category) {
        break;
      }
    }
    if (swapIndex < 0) {
      return;
    }

    rows[index] = rows[swapIndex];
    rows[swapIndex] = current;

    this.setData({
      rows: this.markHeaders(this.reindex(rows)),
      dirty: true
    });
  },

  onSourceChange(e) {
    const index = Number(e.currentTarget.dataset.index);
    const value = Number(e.detail.value);
    const row = this.data.rows[index];
    if (!row) {
      return;
    }
    this.setData({
      ['rows[' + index + '].sourceIndex']: value,
      ['rows[' + index + '].selectedSource']: row.sourceCodes[value] || '',
      dirty: true
    });
  },

  onSave() {
    const rows = this.data.rows;
    const payload = rows.map((r) => ({
      metricCode: r.metricCode,
      enabled: !!r.enabled,
      showInHome: !!r.showInHome,
      showInWatchlist: !!r.showInWatchlist,
      showInDetail: !!r.showInDetail,
      filterable: !!r.filterable,
      sortable: !!r.sortable,
      recordHistory: !!r.recordHistory,
      sortOrder: r.sortOrder,
      precision: r.precision,
      selectedSource: r.selectedSource || null,
      displayName: r.name,
      unit: r.unit
    }));

    preferenceApi
      .save(payload)
      .then(() => {
        app.globalData.preferenceDirty = true;
        this.setData({ dirty: false });
        wx.showToast({ title: '已保存', icon: 'success' });
      })
      .catch(() => {});
  },

  onReset() {
    wx.showModal({
      title: '重置指标偏好',
      content: '将恢复系统默认的指标展示设置，当前的自定义配置会丢失。',
      confirmText: '重置',
      confirmColor: '#b3272d',
      success: (res) => {
        if (!res.confirm) {
          return;
        }
        preferenceApi
          .reset()
          .then(() => {
            app.globalData.preferenceDirty = true;
            wx.showToast({ title: '已重置', icon: 'success' });
            this.load();
          })
          .catch(() => {});
      }
    });
  }
});
