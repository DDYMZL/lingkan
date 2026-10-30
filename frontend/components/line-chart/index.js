/**
 * 轻量折线图（Canvas 2D）
 *
 * 只做一件事：把 [{date, value}] 画成带面积填充的折线，并标注最高/最低/最新。
 * 不引入任何图表库，保持小程序包体积与渲染开销可控。
 */
function hexToRgb(hex) {
  let h = String(hex || '').replace('#', '');
  if (h.length === 3) {
    h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
  }
  const num = parseInt(h, 16);
  if (isNaN(num) || h.length !== 6) {
    return '179, 39, 45';
  }
  return [(num >> 16) & 255, (num >> 8) & 255, num & 255].join(', ');
}

Component({
  properties: {
    points: {
      type: Array,
      value: [],
      observer() {
        this.draw();
      }
    },
    stroke: { type: String, value: '#b3272d' },
    chartHeight: { type: Number, value: 240 },
    unit: { type: String, value: '' }
  },

  data: {
    hasData: false
  },

  lifetimes: {
    ready() {
      this.initCanvas();
    },
    detached() {
      this._ctx = null;
      this._canvas = null;
    }
  },

  methods: {
    initCanvas() {
      const query = wx.createSelectorQuery().in(this);
      query
        .select('#lkChart')
        .fields({ node: true, size: true })
        .exec((res) => {
          const item = res && res[0];
          if (!item || !item.node) {
            return;
          }
          const canvas = item.node;
          const ctx = canvas.getContext('2d');
          let dpr = 2;
          try {
            const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
            dpr = info.pixelRatio || 2;
          } catch (e) {
            dpr = 2;
          }
          canvas.width = item.width * dpr;
          canvas.height = item.height * dpr;
          ctx.scale(dpr, dpr);

          this._canvas = canvas;
          this._ctx = ctx;
          this._w = item.width;
          this._h = item.height;
          this.draw();
        });
    },

    draw() {
      const ctx = this._ctx;
      if (!ctx || !this._w) {
        return;
      }

      const raw = (this.properties.points || []).filter(
        (p) => p && p.value !== null && p.value !== undefined && isFinite(Number(p.value))
      );

      const hasData = raw.length >= 2;
      if (hasData !== this.data.hasData) {
        this.setData({ hasData: hasData });
      }

      const w = this._w;
      const h = this._h;
      ctx.clearRect(0, 0, w, h);
      if (!hasData) {
        return;
      }

      const values = raw.map((p) => Number(p.value));
      let min = Math.min.apply(null, values);
      let max = Math.max.apply(null, values);
      if (min === max) {
        min = min - 1;
        max = max + 1;
      }

      const padL = 10;
      const padR = 10;
      const padT = 22;
      const padB = 24;
      const plotW = w - padL - padR;
      const plotH = h - padT - padB;

      const xOf = (i) => padL + (plotW * i) / (raw.length - 1);
      const yOf = (v) => padT + plotH * (1 - (v - min) / (max - min));

      // 水平参考线
      ctx.strokeStyle = '#f0f1f3';
      ctx.lineWidth = 1;
      for (let g = 0; g <= 2; g++) {
        const y = padT + (plotH * g) / 2;
        ctx.beginPath();
        ctx.moveTo(padL, y);
        ctx.lineTo(w - padR, y);
        ctx.stroke();
      }

      // 面积填充
      const stroke = this.properties.stroke || '#b3272d';
      const rgb = hexToRgb(stroke);
      const gradient = ctx.createLinearGradient(0, padT, 0, h - padB);
      gradient.addColorStop(0, 'rgba(' + rgb + ', 0.18)');
      gradient.addColorStop(1, 'rgba(' + rgb + ', 0.01)');
      ctx.beginPath();
      ctx.moveTo(xOf(0), yOf(values[0]));
      for (let i = 1; i < raw.length; i++) {
        ctx.lineTo(xOf(i), yOf(values[i]));
      }
      ctx.lineTo(xOf(raw.length - 1), h - padB);
      ctx.lineTo(xOf(0), h - padB);
      ctx.closePath();
      ctx.fillStyle = gradient;
      ctx.fill();

      // 折线
      ctx.beginPath();
      ctx.moveTo(xOf(0), yOf(values[0]));
      for (let i = 1; i < raw.length; i++) {
        ctx.lineTo(xOf(i), yOf(values[i]));
      }
      ctx.strokeStyle = this.properties.stroke || '#b3272d';
      ctx.lineWidth = 1.6;
      ctx.lineJoin = 'round';
      ctx.stroke();

      // 最新点
      const lastX = xOf(raw.length - 1);
      const lastY = yOf(values[raw.length - 1]);
      ctx.beginPath();
      ctx.arc(lastX, lastY, 3, 0, Math.PI * 2);
      ctx.fillStyle = this.properties.stroke || '#b3272d';
      ctx.fill();
      ctx.beginPath();
      ctx.arc(lastX, lastY, 6, 0, Math.PI * 2);
      ctx.strokeStyle = 'rgba(179, 39, 45, 0.25)';
      ctx.lineWidth = 2;
      ctx.stroke();

      // 文本标注
      const unit = this.properties.unit || '';
      ctx.font = '10px sans-serif';
      ctx.textBaseline = 'alphabetic';

      ctx.fillStyle = '#9aa0a6';
      ctx.textAlign = 'left';
      ctx.fillText('最高 ' + max.toFixed(2) + unit, padL, padT - 8);

      ctx.textAlign = 'right';
      ctx.fillText('最低 ' + min.toFixed(2) + unit, w - padR, padT - 8);

      const firstDate = String(raw[0].date || '').slice(0, 10);
      const lastDate = String(raw[raw.length - 1].date || '').slice(0, 10);
      ctx.fillStyle = '#b6bbc0';
      ctx.textAlign = 'left';
      ctx.fillText(firstDate, padL, h - 8);
      ctx.textAlign = 'right';
      ctx.fillText(lastDate, w - padR, h - 8);
    }
  }
});
