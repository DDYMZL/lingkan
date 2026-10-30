-- V3: 数据源登记与指标绑定初始化
-- 依据：2026-10-30 实际接口测试结果（见 docs/specs/004-data-source/source-research.md）
-- 原则：只有经过真实请求验证的接口才 enabled=TRUE；未验证的一律 FALSE，并在 description 中写明原因。

INSERT INTO data_providers
(provider_code, provider_name, provider_type, base_url, enabled, requires_token, request_interval_ms,
 health_status, supported_metrics, description, verified, sort_order)
VALUES
('eastmoney', '东方财富', 'HTTP_API', 'https://push2.eastmoney.com', TRUE, FALSE, 500, 'UNKNOWN',
 'latest_price,change_percent,change_amount,open_price,high_price,low_price,pre_close,volume,amount,turnover_rate,volume_ratio,total_market_cap,circulating_market_cap,pb',
 '实测 2026-10-30：全市场列表、单股快照、历史K线、分红送配、财报接口均返回 HTTP 200。价格字段为分（需/100），成交量单位为手。', TRUE, 10),

('tencent', '腾讯财经', 'HTTP_API', 'https://qt.gtimg.cn', TRUE, FALSE, 500, 'UNKNOWN',
 'latest_price,change_percent,change_amount,open_price,high_price,low_price,pre_close,volume,amount,turnover_rate,amplitude,total_market_cap,circulating_market_cap,pb',
 '实测 2026-10-30：单次请求可批量返回沪/深/北三只股票行情（HTTP 200，GBK 编码）。作为批量实时行情首选源。', TRUE, 20),

('sina', '新浪财经', 'HTTP_API', 'https://hq.sinajs.cn', FALSE, FALSE, 1000, 'UNKNOWN',
 'latest_price,open_price,high_price,low_price,pre_close,volume,amount',
 '实测 2026-10-30：需携带 Referer: https://finance.sina.com.cn 才返回 200，否则被拒。定位为行情辅助验证源，默认停用。', TRUE, 30),

('cninfo', '巨潮资讯', 'HTTP_API', 'http://www.cninfo.com.cn', FALSE, FALSE, 1000, 'UNKNOWN',
 '',
 '实测 2026-10-30：公告查询接口返回 200 且返回公告列表 JSON（贵州茅台 1284 条），但为公告索引而非结构化财务字段，需额外解析流程，暂不参与取数。', TRUE, 40),

('legulegu', '乐咕乐股', 'HTTP_API', 'https://legulegu.com', FALSE, FALSE, 2000, 'UNKNOWN',
 '',
 '实测 2026-10-30：常见接口路径均返回 404，未取得可用公开接口，历史股息率来源待确定。', FALSE, 50),

('tushare', 'Tushare', 'HTTP_API', 'https://api.tushare.pro', FALSE, TRUE, 1000, 'UNKNOWN',
 '',
 '实测 2026-10-30：接口可达，返回 code=40101「您上传Token！」。需申请 Token，部分接口需要积分。', TRUE, 60),

('akshare', 'AKShare 采集器', 'PYTHON_COLLECTOR', '', FALSE, FALSE, 1000, 'UNKNOWN',
 '',
 'AKShare 为 Python 开源库而非行情供应商，需以独立采集容器方式接入，通过 HTTP 回传结果，尚未部署。', FALSE, 70),

('baostock', 'BaoStock', 'PYTHON_COLLECTOR', '', FALSE, FALSE, 1000, 'UNKNOWN',
 '',
 'BaoStock 为 Python SDK（非 HTTP 接口），适合历史行情研究，需经 Python 采集器接入。', FALSE, 80);

-- ===== 指标与数据源绑定（初始推荐方案，均可通过小程序调整） =====
INSERT INTO metric_source_bindings (metric_code, provider_code, priority, enabled, is_primary, timeout_ms, cache_seconds)
VALUES
('latest_price', 'tencent', 10, TRUE, TRUE, 5000, 20),
('latest_price', 'eastmoney', 20, TRUE, FALSE, 5000, 20),
('latest_price', 'sina', 30, TRUE, FALSE, 5000, 20),

('change_percent', 'tencent', 10, TRUE, TRUE, 5000, 20),
('change_percent', 'eastmoney', 20, TRUE, FALSE, 5000, 20),

('change_amount', 'tencent', 10, TRUE, TRUE, 5000, 20),
('change_amount', 'eastmoney', 20, TRUE, FALSE, 5000, 20),

('open_price', 'tencent', 10, TRUE, TRUE, 5000, 20),
('open_price', 'eastmoney', 20, TRUE, FALSE, 5000, 20),
('open_price', 'sina', 30, TRUE, FALSE, 5000, 20),

('high_price', 'tencent', 10, TRUE, TRUE, 5000, 20),
('high_price', 'eastmoney', 20, TRUE, FALSE, 5000, 20),
('high_price', 'sina', 30, TRUE, FALSE, 5000, 20),

('low_price', 'tencent', 10, TRUE, TRUE, 5000, 20),
('low_price', 'eastmoney', 20, TRUE, FALSE, 5000, 20),
('low_price', 'sina', 30, TRUE, FALSE, 5000, 20),

('pre_close', 'tencent', 10, TRUE, TRUE, 5000, 20),
('pre_close', 'eastmoney', 20, TRUE, FALSE, 5000, 20),
('pre_close', 'sina', 30, TRUE, FALSE, 5000, 20),

('volume', 'tencent', 10, TRUE, TRUE, 5000, 20),
('volume', 'eastmoney', 20, TRUE, FALSE, 5000, 20),

('amount', 'tencent', 10, TRUE, TRUE, 5000, 20),
('amount', 'eastmoney', 20, TRUE, FALSE, 5000, 20),

('turnover_rate', 'eastmoney', 10, TRUE, TRUE, 5000, 20),
('turnover_rate', 'tencent', 20, TRUE, FALSE, 5000, 20),

('volume_ratio', 'eastmoney', 10, TRUE, TRUE, 5000, 20),

('amplitude', 'tencent', 10, TRUE, TRUE, 5000, 20),

('total_market_cap', 'eastmoney', 10, TRUE, TRUE, 5000, 60),
('total_market_cap', 'tencent', 20, TRUE, FALSE, 5000, 60),

('circulating_market_cap', 'eastmoney', 10, TRUE, TRUE, 5000, 60),
('circulating_market_cap', 'tencent', 20, TRUE, FALSE, 5000, 60),

('pb', 'eastmoney', 10, TRUE, TRUE, 5000, 60);
