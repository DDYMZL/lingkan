/**
 * 状态标签
 * 用于指标状态（OK / MISSING / STALE / PENDING ...）与采集状态
 */
const format = require('../../utils/format');

Component({
  options: {
    addGlobalClass: false
  },

  properties: {
    status: {
      type: String,
      value: '',
      observer(value) {
        this.update(value, this.properties.text);
      }
    },
    text: {
      type: String,
      value: '',
      observer(value) {
        this.update(this.properties.status, value);
      }
    }
  },

  data: {
    label: '',
    tone: 'muted'
  },

  lifetimes: {
    attached() {
      this.update(this.properties.status, this.properties.text);
    }
  },

  methods: {
    update(status, text) {
      const meta = format.statusMeta(status);
      this.setData({
        label: text || meta.text,
        tone: meta.tone
      });
    }
  }
});
