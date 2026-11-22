// MT5 trading for InHuman — /api/mt5?action=...
// The bridge link and key live on Vercel → Settings → Environment Variables:
//   MT5_BRIDGE_URL   your bridge, e.g. https://bridge.algoproedge.com
//   MT5_BRIDGE_KEY   optional key, if your bridge asks for one (sent as X-API-Key)
//   CRON_SECRET      optional: protects /api/mt5?action=tick (the 1h30 auto trades)
//
// Every call (except tick) needs { token } = the InHuman account token.
//   POST status                              connection, live account, target, auto settings, last trades
//   POST connect   { login, password, server, broker }   → bridge /connect (password is never stored)
//   POST disconnect
//   POST price     { symbol }                → bid / ask
//   POST analyze   { symbol }                → BUY/SELL, confidence, TP, SL, reasons   (3.5 credits)
//   POST execute   { symbol, direction, lot, count, tp, sl }  → opens the trades       (1.5 credits per trade)
//   POST voice     { symbol, count, lot, direction? }  analyze (if needed) + execute, for voice commands
//   POST auto      { on, symbol, lot, count }  auto trades every 1h30 until the target
//   POST target    { multiplier }            2.5, 2 or 1.5
//   GET  tick      cron: auto trades, target reached / account lost emails
const { cors, readBody, callAI, parseJsonReply } = require('./_ai');
const { dbReady, cmd, getJSON, setJSON } = require('./_db');
const B = require('./_billing');
const Mail = require('./_mail');

const crypto = require('crypto');
const COMMENT = 'InHuman AI';

// ---- the MT5 password is kept encrypted so the server can reconnect by itself when the bridge drops the session ----
function secretKey() {
  const seed = process.env.MT5_SECRET || process.env.CRON_SECRET || process.env.REDIS_URL || process.env.KV_URL || 'inhuman-mt5';
  return crypto.createHash('sha256').update(String(seed)).digest();
}
function seal(text) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', secretKey(), iv);
  const enc = Buffer.concat([c.update(String(text), 'utf8'), c.final()]);
  return [iv, c.getAuthTag(), enc].map((x) => x.toString('base64')).join('.');
}
function unseal(v) {
  try {
    const [iv, tag, enc] = String(v || '').split('.').map((x) => Buffer.from(x, 'base64'));
    const d = crypto.createDecipheriv('aes-256-gcm', secretKey(), iv);
    d.setAuthTag(tag);
    return Buffer.concat([d.update(enc), d.final()]).toString('utf8');
  } catch (e) { return ''; }
}
const AUTO_EVERY = 90 * 60 * 1000;
const TARGETS = [2.5, 2, 1.5];
const round = (n, d = 2) => { const f = Math.pow(10, d); return Math.round(Number(n) * f) / f; };
const num = (...v) => { for (const x of v) { const n = Number(x); if (x !== undefined && x !== null && x !== '' && Number.isFinite(n)) return n; } return null; };

// ---------------- bridge ----------------
function bridgeBase(cfg) {
  let u = process.env.MT5_BRIDGE_URL || process.env.BRIDGE_URL || process.env.MT5_BRIDGE || '';
  if (!u) {
    const k = Object.keys(process.env).find((x) => /BRIDGE/i.test(x) && /^https?:\/\//.test(process.env[x] || ''));
    if (k) u = process.env[k];
  }
  if (!u && cfg && cfg.bridge) u = cfg.bridge.url || '';
  return String(u).trim().replace(/\/+$/, '');
}
function bridgeKey(cfg) {
  const k = process.env.MT5_BRIDGE_KEY || process.env.MT5_BRIDGE_TOKEN || process.env.BRIDGE_KEY || process.env.BRIDGE_TOKEN || process.env.MT5_API_KEY || '';
  return String(k || (cfg && cfg.bridge && cfg.bridge.token) || '').trim();
}

/** Your own bridge (…/partner/v1/mt5): the bridge key is always the login, the account goes in as ?id= */
const isPartner = (cfg) => /\/partner\/v\d+\/mt5\/?$/i.test(bridgeBase(cfg) || '');

async function bridge(cfg, path, opts = {}) {
  if (isPartner(cfg)) return bridgeOnce(cfg, path, opts, 1);
  let r = await bridgeOnce(cfg, path, opts, 0);
  // "Unauthorized": the bridge wants its own key (not the account token) in the login header → try that, then no header
  if ((r.status === 401 || r.status === 403) && opts.token) {
    r = await bridgeOnce(cfg, path, opts, 1);
    if (r.status === 401 || r.status === 403) r = await bridgeOnce(cfg, path, opts, 2);
  }
  return r;
}

async function bridgeOnce(cfg, path, { method = 'GET', token = '', body, query } = {}, authMode = 0) {
  const base = bridgeBase(cfg);
  if (!base) return { status: 0, data: { error: 'The MT5 bridge link is not set on Vercel (MT5_BRIDGE_URL).' } };
  // the account token goes everywhere a bridge might look: ?token= on every call, the body, and the headers
  const qs = query ? '?' + new URLSearchParams(Object.entries({ ...query, ...(token ? { token, id: token } : {}) }).filter(([, v]) => v !== undefined && v !== '')).toString() : (token ? `?id=${encodeURIComponent(token)}&token=${encodeURIComponent(token)}` : '');
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json', 'ngrok-skip-browser-warning': '1' };
  const key = bridgeKey(cfg);
  if (key) { headers['X-API-Key'] = key; headers['X-Bridge-Key'] = key; }
  if (token) headers['X-Connection-Token'] = token;
  if (authMode === 0) { if (token) headers.Authorization = `Bearer ${token}`; else if (key) headers.Authorization = `Bearer ${key}`; }
  else if (authMode === 1 && key) headers.Authorization = `Bearer ${key}`;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 15000);
  try {
    const r = await fetch(`${base}/${path}${qs}`, {
      method, headers, signal: ctrl.signal,
      body: method === 'GET' ? undefined : JSON.stringify({ ...(body || {}), ...(token ? { token, connection_token: token, id: token } : {}) })
    });
    const text = await r.text();
    let data;
    try { data = JSON.parse(text); } catch (e) { data = { error: text.slice(0, 200) || `HTTP ${r.status}` }; }
    if (data === null || typeof data !== 'object') data = { value: data };
    return { status: r.status, data };
  } catch (e) {
    return { status: 0, data: { error: e.name === 'AbortError' ? 'The bridge took too long to answer' : 'Could not reach the bridge' } };
  } finally {
    clearTimeout(timer);
  }
}
/** Find the connection token wherever the bridge put it (token, connection_token, data.session_id, …) */
function findToken(d) {
  const seen = [];
  const walk = (o, depth) => {
    if (!o || typeof o !== 'object' || depth > 3) return;
    for (const [k, v] of Object.entries(o)) {
      if (typeof v === 'string' && v.length >= 6 && v.length <= 600) seen.push([k.toLowerCase(), v]);
      else if (typeof v === 'number' && /token|session|conn/.test(k.toLowerCase())) seen.push([k.toLowerCase(), String(v)]);
      else if (v && typeof v === 'object') walk(v, depth + 1);
    }
  };
  walk(d, 0);
  const pick = (re) => (seen.find(([k]) => re.test(k)) || [])[1];
  return pick(/token/) || pick(/connection|conn_?id/) || pick(/session/) || pick(/api_?key|^key$/) || pick(/uuid|account_?id|^id$/) || '';
}
const good = (r) => r.status >= 200 && r.status < 300 && !(r.data && (r.data.error || r.data.success === false));

/** Try a few common endpoint names (bridges differ). Remembers what worked. */
const found = {};
async function tryPaths(cfg, kind, paths, opts) {
  const list = found[kind] ? [found[kind], ...paths.filter((p) => p !== found[kind])] : paths;
  let last = null;
  for (const p of list) {
    const r = await bridge(cfg, p, opts);
    if (good(r)) { found[kind] = p; return r; }
    last = r;
    if (r.status === 0) break;                      // bridge down: don't try the rest
  }
  return last || { status: 0, data: { error: 'No answer' } };
}

function pickAccount(d) {
  const a = (d && (d.account || d.info || d.data || d.result)) || d || {};
  const balance = num(a.balance, d.balance);
  const equity = num(a.equity, d.equity, balance);
  return {
    login: String(a.login || d.login || ''),
    name: String(a.name || d.name || ''),
    server: String(a.server || d.server || ''),
    company: String(a.company || d.company || ''),
    currency: String(a.currency || d.currency || 'USD'),
    leverage: num(a.leverage, d.leverage),
    balance,
    equity,
    margin: num(a.margin, d.margin),
    freeMargin: num(a.margin_free, a.free_margin, a.freeMargin, a.marginFree, d.margin_free, d.free_margin, d.freeMargin),
    floating: num(a.profit, a.floating, a.floating_pl, a.floatingPL, d.profit, d.floating, equity != null && balance != null ? equity - balance : null)
  };
}

function pickTick(d) {
  const t = (d && (d.tick || d.price || d.data || d.result || d.quote)) || d || {};
  const bid = num(t.bid, d.bid), ask = num(t.ask, d.ask);
  return { bid, ask, digits: num(t.digits, d.digits), time: num(t.time, d.time) };
}

function pickCandles(d) {
  let rows = Array.isArray(d) ? d : (d && (d.rates || d.candles || d.data || d.bars || d.ohlc || d.result || d.values)) || [];
  if (!Array.isArray(rows)) return [];
  return rows.map((r) => Array.isArray(r)
    ? { o: +r[1], h: +r[2], l: +r[3], c: +r[4] }
    : { o: +(r.open ?? r.o), h: +(r.high ?? r.h), l: +(r.low ?? r.l), c: +(r.close ?? r.c) })
    .filter((x) => [x.o, x.h, x.l, x.c].every(Number.isFinite));
}

/** Ask the bridge for a fresh session with the saved login (when the old token stopped working). */
async function reconnect(cfg, ownerId, s) {
  const password = unseal(s && s.pw);
  if (!password || !s.login || !s.server) return false;
  const r = await bridge(cfg, 'connect', { method: 'POST', body: { login: s.login, password, server: s.server, account: s.login, broker: s.broker || '' } });
  const token = good(r) ? findToken(r.data || {}) : '';
  if (!token || /code:|OLD_VERSION|INVALID_/.test(token)) return false;
  s.token = token;
  s.reconnects = (s.reconnects || 0) + 1;
  await saveState(ownerId, s);
  return true;
}
/** Account info; if the session dropped, reconnect once and try again. */
async function liveAccount(cfg, ownerId, s) {
  let acc = await getAccount(cfg, s.token);
  if (acc.account) return acc;
  if (await reconnect(cfg, ownerId, s)) acc = await getAccount(cfg, s.token);
  return acc;
}

async function getAccount(cfg, token) {
  const r = await tryPaths(cfg, 'account', ['account', 'account_info', 'accountinfo'], { token });
  if (!good(r)) return { error: (r.data && r.data.error) || `Bridge error ${r.status}` };
  const acc = pickAccount(r.data);
  if (acc.balance == null) return { error: 'The bridge did not send a balance' };
  return { account: acc };
}

const SUFFIXES = ['', 'm', '.', 'c', '.m', '+', '-ecn', '.pro', 'pro', '.raw'];
const PRICE_PATHS = ['price', 'tick', 'symbol_info_tick', 'quote', 'symbol'];
/** "xauusd" → "XAUUSD"; "XAUUSDm" stays (broker suffix) */
const ALIASES = { gold: 'XAUUSD', silver: 'XAGUSD', bitcoin: 'BTCUSD', btc: 'BTCUSD', ethereum: 'ETHUSD', eth: 'ETHUSD', oil: 'USOIL', nasdaq: 'USTEC', nas100: 'USTEC', us100: 'USTEC', dow: 'US30', dowjones: 'US30', sp500: 'US500', spx: 'US500', euro: 'EURUSD', pound: 'GBPUSD', cable: 'GBPUSD', yen: 'USDJPY', fiber: 'EURUSD' };
function cleanSym(v) {
  const raw = String(v || '').trim().toLowerCase().replace(/[^a-z0-9]/g, '');
  if (ALIASES[raw]) return ALIASES[raw];
  let t = String(v || '').trim().replace(/[^A-Za-z0-9.+_#-]/g, '').slice(0, 24);
  if (t === t.toLowerCase()) t = t.toUpperCase();
  else t = t.slice(0, 6).toUpperCase() + t.slice(6);
  return t;
}
/** Read the bridge's own list of routes (FastAPI /openapi.json) once, to find its price / candles routes by name. */
let routeList = null;
async function bridgeRoutes(cfg) {
  if (routeList) return routeList;
  routeList = [];
  for (const p of ['openapi.json', 'docs/openapi.json', 'routes', '']) {
    const r = await bridge(cfg, p, {});
    const d = r.data || {};
    const paths = d.paths ? Object.keys(d.paths) : Array.isArray(d.routes) ? d.routes.map((x) => (typeof x === 'string' ? x : x.path || x.rule || '')) : Array.isArray(d.endpoints) ? d.endpoints.map(String) : [];
    if (paths.length) { routeList = paths.map((x) => String(x).replace(/^\/+/, '')); break; }
  }
  return routeList;
}

/** Try one symbol on one route in the common styles: ?symbol=X, /route/X, POST {symbol} */
async function priceFrom(cfg, token, route, sym) {
  const styles = route.includes('{')
    ? [() => bridge(cfg, route.replace(/\{[^}]+\}/, encodeURIComponent(sym)), { token, query: {} })]
    : [() => bridge(cfg, route, { token, query: { symbol: sym, id: token } }),
       () => bridge(cfg, `${route}/${encodeURIComponent(sym)}`, { token, query: {} }),
       () => bridge(cfg, route, { method: 'POST', token, body: { symbol: sym } })];
  for (const go of styles) {
    const r = await go();
    if (r.status === 0) return { down: r.data.error };
    if (good(r)) { const t = pickTick(r.data); if (t.bid != null || t.ask != null) return { bid: t.bid ?? t.ask, ask: t.ask ?? t.bid, digits: t.digits }; }
  }
  return null;
}

async function partnerQuote(cfg, token, sym) {
  const r = await bridge(cfg, 'symbols', { token, query: { action: 'quote', symbol: sym } });
  if (r.status === 0) return { down: r.data.error };
  if (!good(r)) return null;
  const t = pickTick(r.data);
  return t.bid != null || t.ask != null ? { bid: t.bid ?? t.ask, ask: t.ask ?? t.bid, digits: t.digits } : null;
}

async function getPrice(cfg, token, symbol) {
  symbol = cleanSym(symbol);
  if (isPartner(cfg)) {
    const list = /[a-z.+#_-]/.test(symbol.slice(6)) || symbol.length > 7 ? [''] : SUFFIXES;
    for (const sfx of list) {
      const p = await partnerQuote(cfg, token, symbol + sfx);
      if (p && p.down) return { error: p.down };
      if (p) return { symbol: symbol + sfx, ...p, source: 'broker' };
    }
  }
  const tries = /[a-z.+#_-]/.test(symbol.slice(6)) || symbol.length > 7 ? [''] : SUFFIXES;   // already has a suffix
  let routes = ['GetQuote', 'getquote', 'Quote'].concat(PRICE_PATHS, ['symbol_info', 'symbol_price', 'market_price', 'get_price', 'prices']);
  const listed = (await bridgeRoutes(cfg)).filter((p) => /price|tick|quote|bid|ask|symbol/i.test(p) && !/order|history|connect|account/i.test(p));
  routes = Array.from(new Set([...(found.priceRoute ? [found.priceRoute] : []), ...listed, ...routes]));
  for (const sfx of tries) {
    const sym = symbol + sfx;
    const useRoutes = found.priceRoute ? [found.priceRoute] : (sfx === '' ? routes : routes.slice(0, listed.length + 2));
    for (const route of useRoutes) {
      const p = await priceFrom(cfg, token, route, sym);
      if (p && p.down) return { error: p.down };
      if (p) { found.priceRoute = route; return { symbol: sym, ...p, source: 'broker' }; }
    }
  }
  // the bridge has no price route: use the public market price so analysis still works (broker prices can differ a little)
  const m = await marketPrice(symbol);
  if (m) return { symbol, ...m, source: 'market' };
  return { error: `No price for ${symbol}. Your bridge has no price route, and no public price was found for this symbol.` };
}

// ---- public market data (Yahoo Finance), only when the bridge gives no prices ----
const YAHOO = { XAUUSD: ['XAUUSD=X', 'GC=F'], XAGUSD: ['XAGUSD=X', 'SI=F'], BTCUSD: 'BTC-USD', ETHUSD: 'ETH-USD', USOIL: 'CL=F', UKOIL: 'BZ=F', USTEC: 'NQ=F', NAS100: 'NQ=F', US30: 'YM=F', US500: 'ES=F', SPX500: 'ES=F', GER40: '^GDAXI', UK100: '^FTSE', JP225: '^N225' };
function yahooTicker(symbol) {
  const base = cleanSym(symbol).replace(/[^A-Z0-9]/g, '').slice(0, 6);
  const t = YAHOO[base] || YAHOO[cleanSym(symbol)] || (/^[A-Z]{6}$/.test(base) ? `${base}=X` : '');
  return [].concat(t || []);      // spot first (closest to your broker), futures as a backup
}
async function yahoo(symbol, interval, range) {
  for (const t of yahooTicker(symbol)) {
    try {
      const r = await fetch(`https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(t)}?interval=${interval}&range=${range}`, { headers: { 'User-Agent': 'Mozilla/5.0' } });
      const d = await r.json();
      const res = d && d.chart && d.chart.result && d.chart.result[0];
      if (res && res.meta && res.meta.regularMarketPrice) return res;
    } catch (e) {}
  }
  return null;
}
async function marketPrice(symbol) {
  const res = await yahoo(symbol, '1m', '1d');
  const p = res && res.meta && Number(res.meta.regularMarketPrice);
  if (!p) return null;
  const digits = p > 50 ? 2 : 5;
  return { bid: round(p, digits), ask: round(p, digits), digits };
}
async function marketCandles(symbol, timeframe) {
  const map = { M15: ['15m', '5d'], H1: ['60m', '1mo'], H4: ['60m', '3mo'] };
  const [iv, rg] = map[timeframe] || ['60m', '1mo'];
  const res = await yahoo(symbol, iv, rg);
  const q = res && res.indicators && res.indicators.quote && res.indicators.quote[0];
  if (!q) return [];
  let rows = (q.close || []).map((c, i) => ({ o: +q.open[i], h: +q.high[i], l: +q.low[i], c: +c })).filter((x) => [x.o, x.h, x.l, x.c].every(Number.isFinite));
  if (timeframe === 'H4') {      // 4 hourly bars → 1
    const out = [];
    for (let i = 0; i + 3 < rows.length; i += 4) { const g = rows.slice(i, i + 4); out.push({ o: g[0].o, h: Math.max(...g.map((x) => x.h)), l: Math.min(...g.map((x) => x.l)), c: g[3].c }); }
    rows = out;
  }
  return rows.slice(-150);
}

async function getCandles(cfg, token, symbol, timeframe, count = 150) {
  const r = await tryPaths(cfg, 'rates', ['rates', 'candles', 'copy_rates', 'ohlc', 'history_rates'], { token, query: { symbol, timeframe, tf: timeframe, count, bars: count } });
  const rows = good(r) ? pickCandles(r.data) : [];
  return rows.length >= 20 ? rows : marketCandles(symbol, timeframe);
}

async function getLosses(cfg, token, sinceMs) {
  const from = Math.floor(sinceMs / 1000);
  const r = await tryPaths(cfg, 'history', ['history', 'deals', 'history_deals'], { token, query: { from, since: from, date_from: from } });
  if (!good(r)) return null;
  const d = r.data;
  const rows = Array.isArray(d) ? d : (d.deals || d.history || d.data || d.result || []);
  if (!Array.isArray(rows)) return null;
  let count = 0, lost = 0, won = 0;
  rows.forEach((x) => {
    if (/balance|credit|deposit/i.test(String(x.type || '')) || Number(x.type) === 2) return;
    const p = num(x.profit) || 0;
    const mine = !x.comment || /inhuman|sl|tp/i.test(String(x.comment));
    if (!mine) return;
    if (p < 0) { count++; lost += p; } else if (p > 0) won++;
  });
  return { count, lost: round(Math.abs(lost)), won };
}

// ---------------- analysis ----------------
function ema(v, n) { const k = 2 / (n + 1); let e = v[0]; for (let i = 1; i < v.length; i++) e = v[i] * k + e * (1 - k); return e; }
function rsi(v, n = 14) {
  if (v.length < n + 2) return 50;
  let g = 0, l = 0;
  for (let i = v.length - n; i < v.length; i++) { const d = v[i] - v[i - 1]; if (d > 0) g += d; else l -= d; }
  if (l === 0) return 100;
  return 100 - 100 / (1 + g / l);
}
function atr(c, n = 14) {
  if (c.length < n + 1) return null;
  let s = 0;
  for (let i = c.length - n; i < c.length; i++) s += Math.max(c[i].h - c[i].l, Math.abs(c[i].h - c[i - 1].c), Math.abs(c[i].l - c[i - 1].c));
  return s / n;
}

function readTimeframe(name, candles) {
  const closes = candles.map((x) => x.c);
  const e20 = ema(closes.slice(-120), 20), e50 = ema(closes.slice(-150), 50);
  const last = closes[closes.length - 1];
  const r = rsi(closes);
  let score = 0;
  score += e20 > e50 ? 1 : -1;
  score += last > e20 ? 1 : -1;
  score += r > 55 ? 1 : r < 45 ? -1 : 0;
  if (r > 75) score -= 1;              // stretched: careful buying
  if (r < 25) score += 1;              // stretched: careful selling
  const trend = e20 > e50 ? 'uptrend' : 'downtrend';
  return { name, score, rsi: round(r, 1), trend, e20, e50, last, text: `${name}: ${trend} (EMA20 ${e20 > e50 ? 'above' : 'below'} EMA50), price ${last > e20 ? 'above' : 'below'} EMA20, RSI ${round(r, 1)}` };
}

async function analyze(cfg, state, symbol) {
  const price = await getPrice(cfg, state.token, symbol);
  if (price.error) return { error: price.error };
  const sym = price.symbol;
  const tfs = [['M15', 1], ['H1', 1.3], ['H4', 1.6]];
  const sets = await Promise.all(tfs.map(([tf]) => getCandles(cfg, state.token, sym, tf)));
  const digits = price.digits != null ? price.digits : (price.bid > 50 ? 2 : 5);
  const mid = (price.bid + price.ask) / 2;
  let direction, confidence, reasons = [], sl, tp, used = [];

  if (sets.some((s) => s.length >= 60)) {
    let total = 0, max = 0;
    const reads = [];
    sets.forEach((s, i) => {
      if (s.length < 60) return;
      const rd = readTimeframe(tfs[i][0], s);
      reads.push(rd);
      used.push(tfs[i][0]);
      total += rd.score * tfs[i][1];
      max += 4 * tfs[i][1];
    });
    if (total === 0) total = reads[reads.length - 1].e20 > reads[reads.length - 1].e50 ? 0.5 : -0.5;
    direction = total > 0 ? 'BUY' : 'SELL';
    confidence = Math.min(95, Math.round(50 + (Math.abs(total) / max) * 45));
    const base = sets[1].length >= 20 ? sets[1] : sets.find((s) => s.length >= 20);
    const a = atr(base) || mid * 0.003;
    const entry = direction === 'BUY' ? price.ask : price.bid;
    sl = round(direction === 'BUY' ? entry - 1.5 * a : entry + 1.5 * a, digits);
    tp = round(direction === 'BUY' ? entry + 2.5 * a : entry - 2.5 * a, digits);
    reasons = reads.map((r) => r.text);
    reasons.push(`Score ${round(total, 1)} out of ±${round(max, 1)} → ${direction} with ${confidence}% confidence.`);
    reasons.push(`ATR (${used.includes('H1') ? 'H1' : used[0]}) is ${round(a, digits)}: SL = entry ${direction === 'BUY' ? '−' : '+'} 1.5 × ATR, TP = entry ${direction === 'BUY' ? '+' : '−'} 2.5 × ATR (risk 1 : 1.67).`);
  } else {
    // bridge has no candles: ask the AI (with web search) for the direction
    used = ['news + price action'];
    try {
      const raw = await callAI({
        system: `You are a short-term market analyst. Time (UTC): ${new Date().toISOString()}. Current ${sym} bid ${price.bid}, ask ${price.ask}.
Use web search for the latest price action and news. Pick BUY or SELL for the next few hours.
Reply ONLY JSON: {"direction":"BUY"|"SELL","confidence":50-95,"reasons":["short","short","short"]}`,
        history: [], message: `Analyse ${sym} now.`, keys: {}, adminKeys: cfg.keys, maxTokens: 400, fast: true
      });
      const o = parseJsonReply(raw) || {};
      direction = String(o.direction || '').toUpperCase() === 'SELL' ? 'SELL' : 'BUY';
      confidence = Math.max(50, Math.min(95, parseInt(o.confidence, 10) || 60));
      reasons = (Array.isArray(o.reasons) ? o.reasons : [String(o.reason || '')]).map((x) => String(x).replace(/\s*\[\d+(?:,\s*\d+)*\]/g, '')).filter(Boolean).slice(0, 4);
    } catch (e) {
      return { error: 'Your bridge sent no candles and no AI key is set, so I could not analyse.' };
    }
    const entry = direction === 'BUY' ? price.ask : price.bid;
    sl = round(direction === 'BUY' ? entry * 0.997 : entry * 1.003, digits);
    tp = round(direction === 'BUY' ? entry * 1.005 : entry * 0.995, digits);
    reasons.push('No candles from the bridge, so SL is 0.3% and TP 0.5% from the entry.');
  }
  if (price.source === 'market') reasons.push('Your bridge sends no prices, so this uses the public market price. Your broker\'s price can differ a little: check the SL and TP before you execute.');
  return { symbol: sym, direction, confidence, sl, tp, bid: price.bid, ask: price.ask, priceSource: price.source || 'broker', timeframes: used, reasons, reason: reasonFor(direction, confidence), at: Date.now() };
}

// ---------------- the reason Jarvis gives (fixed texts, so they're pre-recorded in his voice) ----------------
const REASONS = {
  BUY_STRONG: 'Jarvis sees buyers in full control of this market. The price is holding above its key moving averages on the fifteen minute, one hour and four hour charts. Momentum is pushing upward and every small dip is being bought quickly. Volatility is healthy, so there is room for the move to keep running. The stop loss sits safely below recent support to protect the account. The take profit targets the next area where sellers may step in.',
  BUY_SOFT: 'Jarvis is leaning towards a buy on this market. The short term trend has turned upward, even though the bigger picture is still deciding. Momentum is building slowly and the price is respecting its support. This is a smaller edge, so the position is kept tight and controlled. The stop loss sits below the last swing low in case buyers give up. The take profit aims for the nearest resistance above.',
  SELL_STRONG: 'Jarvis sees sellers in full control of this market. The price is trading below its key moving averages on the fifteen minute, one hour and four hour charts. Momentum is pushing downward and every small bounce is being sold quickly. Volatility is healthy, so there is room for the drop to keep running. The stop loss sits safely above recent resistance to protect the account. The take profit targets the next area where buyers may step in.',
  SELL_SOFT: 'Jarvis is leaning towards a sell on this market. The short term trend has turned downward, even though the bigger picture is still deciding. Momentum is fading and the price is struggling to break its resistance. This is a smaller edge, so the position is kept tight and controlled. The stop loss sits above the last swing high in case sellers give up. The take profit aims for the nearest support below.'
};
const reasonFor = (direction, confidence) => REASONS[`${direction === 'SELL' ? 'SELL' : 'BUY'}_${(confidence || 0) >= 70 ? 'STRONG' : 'SOFT'}`];

// ---------------- trades ----------------
/** Send one order, finding the bridge's order route by itself (ordersend, OrderSend, order, trade, …) and
 *  its style (POST JSON body, or GET with ?symbol=&operation=Buy&volume= like mtapi). Remembers what worked. */
async function sendOrder(cfg, token, body) {
  if (isPartner(cfg)) {
    // POST /trade: the common order layouts, stopping at the first one the bridge accepts (remembered after)
    const side = body.type === 'SELL' ? 'sell' : 'buy';
    const sl = body.sl || 0, tp = body.tp || 0;
    const base = { id: token, symbol: body.symbol, volume: body.volume, sl, tp, stoploss: sl, takeprofit: tp, stop_loss: sl, take_profit: tp,
      stopLoss: sl, takeProfit: tp, sl_price: sl, tp_price: tp, comment: body.comment, magic: body.magic };
    const layouts = [
      { ...base, type: side },
      { ...base, action: side },
      { ...base, side },
      { ...base, type: side.toUpperCase(), action: side, side, order_type: side, lot: body.volume, lots: body.volume, stoploss: base.sl, takeprofit: base.tp },
      { ...base, action: 'market', type: side },
      { ...base, cmd: side === 'buy' ? 0 : 1, type: side === 'buy' ? 0 : 1 }
    ];
    const order = found.tradeLayout != null ? [found.tradeLayout, ...layouts.keys()].filter((v, i, a) => a.indexOf(v) === i) : [...layouts.keys()];
    let last = null;
    for (const k of order) {
      const r = await bridge(cfg, 'trade', { method: 'POST', token, body: layouts[k] });
      if (r.status === 0) return r;
      // 422 = the fields were wrong (validation) → try the next layout; a 400 about the market itself is a real answer
      if (r.status === 422 || (r.status === 400 && !/market (is )?closed|no money|not enough|margin|invalid (volume|stops|price)|retcode/i.test(JSON.stringify(r.data)))) { last = r; continue; }
      found.tradeLayout = k;
      return r;
    }
    return last;
  }
  const dirWord = body.type === 'SELL' ? 'Sell' : 'Buy';
  const q = { symbol: body.symbol, operation: dirWord, type: body.type, side: body.side, volume: body.volume, lot: body.lot,
    stoploss: body.sl, takeprofit: body.tp, sl: body.sl, tp: body.tp, comment: body.comment, magic: body.magic, slippage: body.deviation, id: token };
  const styles = {
    post: (route) => bridge(cfg, route, { method: 'POST', token, body }),
    get: (route) => bridge(cfg, route, { method: 'GET', token, query: q })
  };
  if (found.orderRoute) {
    const r = await styles[found.orderStyle || 'post'](found.orderRoute);
    if (r.status !== 404 && r.status !== 405) return r;
    found.orderRoute = null;
  }
  const listed = (await bridgeRoutes(cfg)).filter((p) => /order|trade|buy|sell|position|deal/i.test(p) && !/history|close|modify|cancel|delete|list|get_|orders$|positions$/i.test(p));
  const routes = Array.from(new Set([...listed, 'ordersend', 'order_send', 'OrderSend', 'order', 'orders/send', 'trade', 'trade/open', 'open_trade',
    'open', 'market_order', 'place_order', 'new_order', 'send_order', body.type === 'SELL' ? 'sell' : 'buy', 'mt5/ordersend', 'api/ordersend']));
  let last = null;
  for (const route of routes) {
    for (const style of ['post', 'get']) {
      const r = await styles[style](route);
      if (r.status === 0) return r;                                   // bridge down: stop
      if (r.status === 404 || r.status === 405) { last = r; continue; } // no such route / method: keep looking
      found.orderRoute = route; found.orderStyle = style;
      return r;
    }
  }
  return last || { status: 404, data: { error: 'The bridge has no order route' } };
}

/** Every trade gets a stop loss and take profit: the given ones if they sit on the right side of the live price,
 *  otherwise safe ones (TP 0.5% away, SL 0.3% away). */
function safeLevels(direction, px, sl, tp) {
  if (!px || px.error || (px.bid == null && px.ask == null)) return { sl, tp };
  const buy = direction === 'BUY';
  const entry = buy ? (px.ask ?? px.bid) : (px.bid ?? px.ask);
  const digits = px.digits != null ? px.digits : (entry > 50 ? 2 : 5);
  const okSl = sl > 0 && (buy ? sl < entry : sl > entry) && Math.abs(sl - entry) / entry < 0.08;
  const okTp = tp > 0 && (buy ? tp > entry : tp < entry) && Math.abs(tp - entry) / entry < 0.15;
  return {
    sl: okSl ? sl : round(buy ? entry * 0.997 : entry * 1.003, digits),
    tp: okTp ? tp : round(buy ? entry * 1.005 : entry * 0.995, digits)
  };
}

async function placeTrades(cfg, state, { symbol, direction, lot, count, sl, tp }) {
  const tickets = [], errors = [];
  for (let i = 0; i < count; i++) {
    const body = {
      symbol, type: direction, action: direction.toLowerCase(), order_type: direction, side: direction.toLowerCase(),
      volume: lot, lot, lots: lot, sl: sl || 0, tp: tp || 0, stoploss: sl || 0, takeprofit: tp || 0,
      stop_loss: sl || 0, take_profit: tp || 0, stopLoss: sl || 0, takeProfit: tp || 0,
      comment: COMMENT, magic: 560056, deviation: 30
    };
    const r = await sendOrder(cfg, state.token, body);
    const d = r.data || {};
    const res = d.result || d.data || d;
    const rc = num(res.retcode, d.retcode);
    const ok = good(r) && (rc == null || rc === 10009 || rc === 10008);
    if (ok) tickets.push(String(res.order || res.ticket || res.deal || res.position || d.order || d.ticket || '—'));
    else errors.push(r.status === 404 ? 'Your bridge has no order route that InHuman could find (tried ordersend, OrderSend, order, trade and more). Send me your bridge route list.' : (d.error || d.detail || d.message || res.comment || d.comment || `HTTP ${r.status}`));
    if (r.status === 0) break;
  }
  return { tickets, errors };
}

async function execute(req, cfg, sub, state, o) {
  // never without a stop loss / take profit (voice trades, auto trades and empty boxes included)
  if (!(o.sl > 0) || !(o.tp > 0)) {
    const px = await getPrice(cfg, state.token, o.symbol);
    o = { ...o, ...safeLevels(o.direction, px, Number(o.sl) || 0, Number(o.tp) || 0) };
  }
  let opened = await placeTrades(cfg, state, o);
  // nothing opened: the bridge may have dropped the session → reconnect and try once more
  if (!opened.tickets.length && sub && sub.client && await reconnect(cfg, sub.client.id, state)) opened = await placeTrades(cfg, state, o);
  if (!opened.tickets.length && !state.pw && opened.errors.some((e) => /not connected|session|token|401|403/i.test(String(e)))) {
    opened.errors.unshift('Your MT5 session expired on the bridge. Enter your MT5 password on the MT5 page and tap Reconnect once, then InHuman keeps it alive by itself.');
  }
  const n = opened.tickets.length;
  let usage = null;
  if (n) usage = await B.charge(sub, cfg, { credits: n * (cfg.costs.trade || 1.5) });
  const rec = {
    at: Date.now(), symbol: o.symbol, direction: o.direction, lot: o.lot, count: o.count, opened: n,
    sl: o.sl, tp: o.tp, confidence: o.confidence || null, timeframes: o.timeframes || [], tickets: opened.tickets, auto: !!o.auto
  };
  state.trades = [rec, ...(state.trades || [])].slice(0, 40);
  await saveState(sub.client.id, state);
  let email = null;
  if (n) email = await Mail.tradeExecuted(req, sub.client, rec);
  return { ...rec, errors: opened.errors, usage, emailSent: !!(email && email.sent) };
}

// ---------------- saved state (per InHuman account) ----------------
const stateKey = (id) => `mt5:${id}`;
async function loadState(id) { return (await getJSON(stateKey(id))) || null; }
async function saveState(id, s) { await setJSON(stateKey(id), s); }

function publicState(s, account) {
  if (!s) return { connected: false };
  const eq = account ? (account.equity ?? account.balance) : null;
  return {
    connected: true,
    connectionToken: s.token,
    login: s.login, server: s.server, broker: s.broker,
    connectedAt: s.connectedAt,
    startBalance: s.startBalance, multiplier: s.multiplier, target: s.target,
    left: eq != null ? round(Math.max(0, s.target - eq)) : null,
    progress: eq != null && s.target > s.startBalance ? Math.max(0, Math.min(100, round(((eq - s.startBalance) / (s.target - s.startBalance)) * 100, 1))) : 0,
    targetHitAt: s.targetHitAt || 0, blownAt: s.blownAt || 0,
    auto: s.auto || { on: false }, lastAutoAt: s.lastAutoAt || 0,
    nextAutoAt: s.auto && s.auto.on && !s.targetHitAt && !s.blownAt ? (s.lastAutoAt || 0) + AUTO_EVERY : 0,
    trades: (s.trades || []).slice(0, 15),
    account: account || null
  };
}

function fmtDuration(ms) {
  const m = Math.max(1, Math.round(ms / 60000));
  const d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60), mm = m % 60;
  return [d && `${d} day${d > 1 ? 's' : ''}`, h && `${h} hour${h > 1 ? 's' : ''}`, mm && `${mm} minute${mm > 1 ? 's' : ''}`].filter(Boolean).join(' ');
}

/** Target reached / account lost: email once and stop auto trading */
async function checkMilestones(req, cfg, client, s, account) {
  if (!account) return;
  const eq = account.equity ?? account.balance;
  let changed = false;
  if (!s.targetHitAt && eq >= s.target) {
    s.targetHitAt = Date.now();
    changed = true;
    const losses = await getLosses(cfg, s.token, s.connectedAt);
    await Mail.targetReached(req, client, { took: fmtDuration(s.targetHitAt - s.connectedAt), losses, account, s });
  }
  if (!s.blownAt && (eq <= Math.max(0.5, s.startBalance * 0.01) || account.balance <= 0)) {
    s.blownAt = Date.now();
    changed = true;
    const losses = await getLosses(cfg, s.token, s.connectedAt);
    await Mail.accountLost(req, client, { account, s, losses });
  }
  if (changed) {
    if (s.auto) s.auto.on = false;
    await cmd('SREM', 'mt5:auto', client.id);
    await saveState(client.id, s);
  }
}

async function autoRun(req, cfg, client, s) {
  const sub = { key: `usage:c:${client.id}`, client };
  const can = await B.check(sub, cfg);
  if (!can.ok) return { skipped: 'no credits' };
  const a = await analyze(cfg, s, s.auto.symbol);
  s.lastAutoAt = Date.now();
  await saveState(client.id, s);
  if (a.error) return { error: a.error };
  await B.charge(sub, cfg, { credits: cfg.costs.analysis || 3.5 });
  return execute(req, cfg, sub, s, { symbol: a.symbol, direction: a.direction, lot: s.auto.lot, count: s.auto.count, sl: a.sl, tp: a.tp, confidence: a.confidence, timeframes: a.timeframes, auto: true });
}

// ---------------- old relay (Android app before this update) ----------------
async function legacy(req, res, b, cfg) {
  const path = String(b.path || '');
  if (!['connect', 'ordersend', 'account'].includes(path)) return res.status(400).json({ error: 'Unknown bridge action' });
  const r = await bridge(cfg, path, { method: path === 'account' ? 'GET' : 'POST', token: b.token || '', body: b.body || {} });
  return res.status(200).json(r);
}

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  const q = req.query || {};
  const action = String(q.action || '');
  try {
    const b = req.method === 'POST' ? readBody(req) : {};
    const cfg = await B.getConfig();
    cfg.costs = { analysis: 3.5, trade: 1.5, ...(cfg.costs || {}) };
    if (b.path && !action) return legacy(req, res, b, cfg);
    if (!dbReady()) return res.status(200).json({ error: 'The database is not connected yet.' });

    // ---- cron: every 30 min from GitHub Actions; trades every 1h30 per account ----
    if (action === 'tick') {
      if (process.env.CRON_SECRET && q.key !== process.env.CRON_SECRET && b.key !== process.env.CRON_SECRET) return res.status(401).json({ error: 'bad key' });
      const ids = (await cmd('SMEMBERS', 'mt5:conn')) || [];
      const out = [];
      const started = Date.now();
      for (const id of ids) {
        if (Date.now() - started > 45000) break;
        const client = await getJSON(`client:${id}`);
        const s = await loadState(id);
        if (!client || !s) { await cmd('SREM', 'mt5:conn', id); continue; }
        const acc = await liveAccount(cfg, id, s);
        if (acc.error) { out.push({ id, error: acc.error }); continue; }
        await checkMilestones(req, cfg, client, s, acc.account);
        if (s.auto && s.auto.on && !s.targetHitAt && !s.blownAt && Date.now() - (s.lastAutoAt || 0) >= AUTO_EVERY - 5 * 60000) {
          const r = await autoRun(req, cfg, client, s);
          out.push({ id, auto: r.error || r.skipped || `${r.opened} trades` });
        } else out.push({ id, ok: true });
      }
      return res.status(200).json({ ok: true, accounts: out.length, out });
    }

    const client = await B.clientByToken(b.token || q.token);
    if (!client) return res.status(200).json({ error: 'Sign in to your InHuman account first.', signIn: true });
    const sub = { key: `usage:c:${client.id}`, client };
    let s = await loadState(client.id);

    if (action === 'connect') {
      const login = String(b.login || '').trim(), password = String(b.password || ''), server = String(b.server || '').trim();
      if (!login || !password || !server) return res.status(200).json({ error: 'Enter your MT5 login, password and broker server.' });
      if (!bridgeBase(cfg)) return res.status(200).json({ error: 'The MT5 bridge link is not set on Vercel yet (MT5_BRIDGE_URL).' });
      const r = await bridge(cfg, 'connect', { method: 'POST', body: { login, password, server, account: login, broker: b.broker || '' } });
      const d = r.data || {};
      // mt5rest puts errors inside the token field: "{message:OLD_VERSION (...),code:OLD_VERSION,stackTrace: ...}"
      const rawText = JSON.stringify(d);
      const code = (rawText.match(/code:\s*([A-Z_]{4,})/) || rawText.match(/\b(OLD_VERSION|INVALID_ACCOUNT|INVALID_PASSWORD|INVALID_SERVER|NO_CONNECTION|TIMEOUT|ACCOUNT_DISABLED)\b/) || [])[1];
      if (code) {
        const why = {
          OLD_VERSION: 'Your bridge is too old for this broker (MT5 says OLD_VERSION). Update mt5rest on your droplet to the newest version, then connect again.',
          INVALID_ACCOUNT: 'Wrong MT5 login or password.', INVALID_PASSWORD: 'Wrong MT5 password.',
          INVALID_SERVER: 'Wrong broker server name.', NO_CONNECTION: 'The bridge could not reach the broker server.',
          TIMEOUT: 'The broker server took too long to answer.', ACCOUNT_DISABLED: 'This MT5 account is disabled by the broker.'
        }[code] || `The bridge said: ${code}`;
        return res.status(200).json({ error: why, code });
      }
      const token = findToken(d);
      if (!good(r)) return res.status(200).json({ error: 'Bridge said: ' + (d.error || d.message || d.detail || `HTTP ${r.status}`) });
      if (!token) {
        const shown = JSON.stringify(d).replace(/"(password|pass|pwd)"\s*:\s*"[^"]*"/gi, '"$1":"***"').slice(0, 300);
        return res.status(200).json({ error: `The bridge answered (HTTP ${r.status}) but sent no connection token. It sent: ${shown}` });
      }
      const acc = await getAccount(cfg, token);
      const inline = pickAccount(d);
      const account = acc.account || (inline.balance != null ? inline : null);
      if (!account) return res.status(200).json({ error: 'Connected, but the bridge sent no balance: ' + (acc.error || '') });
      const mult = TARGETS.includes(Number(b.multiplier)) ? Number(b.multiplier) : (s && s.multiplier) || 2.5;
      const startBalance = account.equity ?? account.balance;
      s = {
        token, login, server, pw: seal(password), broker: String(b.broker || account.company || '').slice(0, 60),
        connectedAt: Date.now(), startBalance, multiplier: mult, target: round(startBalance * mult),
        auto: (s && s.auto) || { on: false, symbol: 'XAUUSD', lot: 0.01, count: 1 }, lastAutoAt: 0,
        targetHitAt: 0, blownAt: 0, trades: (s && s.trades) || []
      };
      await saveState(client.id, s);
      await cmd('SADD', 'mt5:conn', client.id);
      if (s.auto.on) await cmd('SADD', 'mt5:auto', client.id);
      const email = await Mail.mt5Connected(req, client, account, s);
      return res.status(200).json({ ok: true, ...publicState(s, account), emailSent: !!(email && email.sent) });
    }

    if (!s) return res.status(200).json({ connected: false, bridge: !!bridgeBase(cfg), costs: { analysis: cfg.costs.analysis, trade: cfg.costs.trade } });

    if (action === 'status') {
      const acc = await liveAccount(cfg, client.id, s);
      if (acc.account) await checkMilestones(req, cfg, client, s, acc.account);
      // the bridge forgot this account's session and there's no saved password to renew it: say so clearly
      const expired = !acc.account && !s.pw && /not connected|no session|invalid token|unknown token|expired|not found|401|403/i.test(acc.error || '');
      return res.status(200).json({ ...publicState(s, acc.account), accountError: acc.error || '', sessionExpired: expired, bridge: !!bridgeBase(cfg), costs: { analysis: cfg.costs.analysis, trade: cfg.costs.trade } });
    }

    if (action === 'disconnect') {
      await cmd('DEL', stateKey(client.id));
      await cmd('SREM', 'mt5:conn', client.id);
      await cmd('SREM', 'mt5:auto', client.id);
      return res.status(200).json({ ok: true, connected: false });
    }

    if (action === 'price') {
      let p = await getPrice(cfg, s.token, b.symbol || 'XAUUSD');
      if (p.error && await reconnect(cfg, client.id, s)) p = await getPrice(cfg, s.token, b.symbol || 'XAUUSD');
      return res.status(200).json(p);
    }

    if (action === 'target') {
      const m = Number(b.multiplier);
      if (!TARGETS.includes(m)) return res.status(200).json({ error: 'Pick 2.5, 2 or 1.5' });
      const acc = await getAccount(cfg, s.token);
      if (b.reset && acc.account) { s.startBalance = acc.account.equity ?? acc.account.balance; s.connectedAt = Date.now(); s.targetHitAt = 0; s.blownAt = 0; }
      s.multiplier = m;
      s.target = round(s.startBalance * m);
      await saveState(client.id, s);
      return res.status(200).json({ ok: true, ...publicState(s, acc.account) });
    }

    if (action === 'auto') {
      const on = !!b.on;
      s.auto = {
        on,
        symbol: cleanSym(b.symbol || (s.auto && s.auto.symbol) || 'XAUUSD'),
        lot: Math.max(0.01, Math.min(100, Number(b.lot) || (s.auto && s.auto.lot) || 0.01)),
        count: Math.max(1, Math.min(20, parseInt(b.count, 10) || (s.auto && s.auto.count) || 1))
      };
      if (on && (s.targetHitAt || s.blownAt)) return res.status(200).json({ error: s.targetHitAt ? 'The target is already reached. Set a new target first.' : 'The account has no money left.' });
      if (on) { s.lastAutoAt = Date.now(); await cmd('SADD', 'mt5:auto', client.id); }   // first auto trade 1h30 from now
      else await cmd('SREM', 'mt5:auto', client.id);
      await saveState(client.id, s);
      return res.status(200).json({ ok: true, ...publicState(s, null) });
    }

    if (action === 'analyze' || action === 'voice') {
      const can = await B.check(sub, cfg);
      if (!can.ok) return res.status(200).json({ error: can.message, usage: can.usage });
      const symbol = cleanSym(b.symbol);
      if (!symbol) return res.status(200).json({ error: 'Pick a symbol first.' });
      let dir = String(b.direction || '').toUpperCase();
      let a;
      if (action === 'analyze' || (dir !== 'BUY' && dir !== 'SELL')) {
        a = await analyze(cfg, s, symbol);
        if (a.error) return res.status(200).json({ error: a.error });
        const usage = await B.charge(sub, cfg, { credits: cfg.costs.analysis });
        if (action === 'analyze') return res.status(200).json({ ...a, usage });
      } else {
        const p = await getPrice(cfg, s.token, symbol);
        if (p.error) return res.status(200).json({ error: p.error });
        a = { symbol: p.symbol, direction: dir, confidence: null, timeframes: [], sl: 0, tp: 0 };
      }
      const count = Math.max(1, Math.min(20, parseInt(b.count, 10) || 1));
      const lot = Math.max(0.01, Math.min(100, Number(b.lot) || (s.auto && s.auto.lot) || 0.01));
      const r = await execute(req, cfg, sub, s, { symbol: a.symbol, direction: a.direction, lot, count, sl: a.sl, tp: a.tp, confidence: a.confidence, timeframes: a.timeframes });
      return res.status(200).json({ ...r, analysis: a });
    }

    // chart photo / screenshot → symbol, direction, confidence, TP, SL, reason (the Scanner page)
    if (action === 'scan') {
      const can = await B.check(sub, cfg);
      if (!can.ok) return res.status(200).json({ error: can.message, usage: can.usage });
      const m = /^data:(image\/(?:jpeg|png|webp));base64,(.+)$/.exec(String(b.image || ''));
      if (!m || m[2].length > 4500000) return res.status(200).json({ error: 'Send a chart picture (JPEG or PNG, under 3 MB).' });
      let o = {};
      try {
        const raw = await callAI({
          system: `You are JARVIS, a sharp trading analyst. You are shown a picture of a price chart (a screenshot or a photo of a screen).
Read it: which instrument (symbol, e.g. XAUUSD, BTCUSD, EURUSD, US30), the timeframe if visible, the trend, structure, support / resistance and momentum.
You MUST choose BUY or SELL (never wait). Give a confidence from 50 to 95.
If price levels are readable, give a take profit and stop loss at sensible levels; otherwise use 0.
Write "reason": 5 to 6 short plain-English sentences explaining the trade like you're talking to the trader.
Reply ONLY JSON: {"symbol":"XAUUSD","timeframe":"H1","direction":"BUY","confidence":78,"tp":0,"sl":0,"reason":"..."}
If the picture is not a price chart, reply {"error":"That doesn't look like a chart."}`,
          history: [], message: 'Analyse this chart.', keys: {}, adminKeys: cfg.keys, search: false, maxTokens: 700, fast: true,
          image: { mime: m[1], data: m[2] }
        });
        o = parseJsonReply(raw) || {};
      } catch (e) {
        return res.status(200).json({ error: e.code === 'NO_KEY' ? 'No AI key is set for chart reading.' : 'I could not read that picture. Try a clearer screenshot.' });
      }
      if (o.error) return res.status(200).json({ error: String(o.error).slice(0, 120) });
      const symbol = cleanSym(o.symbol || b.symbol || 'XAUUSD');
      const direction = String(o.direction || '').toUpperCase() === 'SELL' ? 'SELL' : 'BUY';
      const confidence = Math.max(50, Math.min(95, parseInt(o.confidence, 10) || 65));
      let tp = Number(o.tp) || 0, sl = Number(o.sl) || 0, bid = null, ask = null, sym = symbol;
      const p = await getPrice(cfg, s.token, symbol);
      if (!p.error) {
        sym = p.symbol; bid = p.bid; ask = p.ask;
        const entry = direction === 'BUY' ? p.ask : p.bid;
        const digits = p.digits != null ? p.digits : (entry > 50 ? 2 : 5);
        const okSide = direction === 'BUY' ? (tp > entry && sl > 0 && sl < entry) : (tp > 0 && tp < entry && sl > entry);
        const near = Math.abs(tp - entry) / entry < 0.06 && Math.abs(sl - entry) / entry < 0.04;
        if (!okSide || !near) {           // levels from the picture don't fit the live price: use safe distances
          tp = round(direction === 'BUY' ? entry * 1.005 : entry * 0.995, digits);
          sl = round(direction === 'BUY' ? entry * 0.997 : entry * 1.003, digits);
        }
      }
      const usage = await B.charge(sub, cfg, { credits: cfg.costs.analysis });
      const reason = String(o.reason || '').replace(/\s*\[\d+(?:,\s*\d+)*\]/g, '').trim() || reasonFor(direction, confidence);
      return res.status(200).json({ symbol: sym, timeframe: String(o.timeframe || '').slice(0, 8), direction, confidence, tp, sl, bid, ask, reason, usage, at: Date.now() });
    }

    if (action === 'execute') {
      const can = await B.check(sub, cfg);
      if (!can.ok) return res.status(200).json({ error: can.message, usage: can.usage });
      const symbol = cleanSym(b.symbol);
      const direction = String(b.direction || '').toUpperCase();
      if (!symbol || !['BUY', 'SELL'].includes(direction)) return res.status(200).json({ error: 'Analyse the symbol first.' });
      const count = Math.max(1, Math.min(50, parseInt(b.count, 10) || 1));
      const lot = Math.max(0.01, Math.min(100, Number(b.lot) || 0.01));
      const px = await getPrice(cfg, s.token, symbol);              // BTCUSD → BTCUSDm etc. (the broker's own name)
      const lv = safeLevels(direction, px, Number(b.sl) || 0, Number(b.tp) || 0);
      const r = await execute(req, cfg, sub, s, {
        symbol: px.symbol && px.source === 'broker' ? px.symbol : symbol, direction, lot, count, sl: lv.sl, tp: lv.tp,
        confidence: num(b.confidence), timeframes: Array.isArray(b.timeframes) ? b.timeframes.slice(0, 5).map(String) : []
      });
      return res.status(200).json(r);
    }

    return res.status(400).json({ error: 'Unknown action' });
  } catch (e) {
    return res.status(200).json({ error: String(e.message || e) });
  }
};
