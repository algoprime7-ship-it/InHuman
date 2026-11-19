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

const COMMENT = 'InHuman AI';
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

async function bridge(cfg, path, { method = 'GET', token = '', body, query } = {}) {
  const base = bridgeBase(cfg);
  if (!base) return { status: 0, data: { error: 'The MT5 bridge link is not set on Vercel (MT5_BRIDGE_URL).' } };
  const qs = query ? '?' + new URLSearchParams(Object.entries({ ...query, ...(token ? { token } : {}) }).filter(([, v]) => v !== undefined && v !== '')).toString() : (token && method === 'GET' ? `?token=${encodeURIComponent(token)}` : '');
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json', 'ngrok-skip-browser-warning': '1' };
  const key = bridgeKey(cfg);
  if (key) { headers['X-API-Key'] = key; headers['X-Bridge-Key'] = key; }
  if (token) { headers.Authorization = `Bearer ${token}`; headers['X-Connection-Token'] = token; }
  else if (key) headers.Authorization = `Bearer ${key}`;
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 15000);
  try {
    const r = await fetch(`${base}/${path}${qs}`, {
      method, headers, signal: ctrl.signal,
      body: method === 'GET' ? undefined : JSON.stringify({ ...(body || {}), ...(token ? { token } : {}) })
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
async function getPrice(cfg, token, symbol) {
  symbol = cleanSym(symbol);
  const tries = /[a-z.+#_-]/.test(symbol.slice(6)) || symbol.length > 7 ? [''] : SUFFIXES;   // already has a suffix
  for (const sfx of tries) {
    const s = symbol + sfx;
    const paths = found.price ? [found.price] : (sfx === '' ? PRICE_PATHS : PRICE_PATHS.slice(0, 2));
    const r = await tryPaths(cfg, 'price', paths, { token, query: { symbol: s } });
    if (r.status === 0) return { error: r.data.error };
    if (good(r)) {
      const t = pickTick(r.data);
      if (t.bid != null || t.ask != null) return { symbol: s, bid: t.bid ?? t.ask, ask: t.ask ?? t.bid, digits: t.digits };
    }
  }
  return { error: `No price for ${symbol}. Check the symbol name your broker uses.` };
}

async function getCandles(cfg, token, symbol, timeframe, count = 150) {
  const r = await tryPaths(cfg, 'rates', ['rates', 'candles', 'copy_rates', 'ohlc', 'history_rates'], { token, query: { symbol, timeframe, tf: timeframe, count, bars: count } });
  return good(r) ? pickCandles(r.data) : [];
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
  return { symbol: sym, direction, confidence, sl, tp, bid: price.bid, ask: price.ask, timeframes: used, reasons, at: Date.now() };
}

// ---------------- trades ----------------
async function placeTrades(cfg, state, { symbol, direction, lot, count, sl, tp }) {
  const tickets = [], errors = [];
  for (let i = 0; i < count; i++) {
    const body = {
      symbol, type: direction, action: direction.toLowerCase(), order_type: direction, side: direction.toLowerCase(),
      volume: lot, lot, lots: lot, sl: sl || 0, tp: tp || 0, stoploss: sl || 0, takeprofit: tp || 0,
      comment: COMMENT, magic: 560056, deviation: 30
    };
    const r = await bridge(cfg, 'ordersend', { method: 'POST', token: state.token, body });
    const d = r.data || {};
    const res = d.result || d.data || d;
    const rc = num(res.retcode, d.retcode);
    const ok = good(r) && (rc == null || rc === 10009 || rc === 10008);
    if (ok) tickets.push(String(res.order || res.ticket || res.deal || res.position || d.order || d.ticket || '—'));
    else errors.push(d.error || res.comment || d.comment || `HTTP ${r.status}`);
    if (r.status === 0) break;
  }
  return { tickets, errors };
}

async function execute(req, cfg, sub, state, o) {
  const opened = await placeTrades(cfg, state, o);
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
        const acc = await getAccount(cfg, s.token);
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
        token, login, server, broker: String(b.broker || account.company || '').slice(0, 60),
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
      const acc = await getAccount(cfg, s.token);
      if (acc.account) await checkMilestones(req, cfg, client, s, acc.account);
      return res.status(200).json({ ...publicState(s, acc.account), accountError: acc.error || '', bridge: !!bridgeBase(cfg), costs: { analysis: cfg.costs.analysis, trade: cfg.costs.trade } });
    }

    if (action === 'disconnect') {
      await cmd('DEL', stateKey(client.id));
      await cmd('SREM', 'mt5:conn', client.id);
      await cmd('SREM', 'mt5:auto', client.id);
      return res.status(200).json({ ok: true, connected: false });
    }

    if (action === 'price') {
      const p = await getPrice(cfg, s.token, b.symbol || 'XAUUSD');
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

    if (action === 'execute') {
      const can = await B.check(sub, cfg);
      if (!can.ok) return res.status(200).json({ error: can.message, usage: can.usage });
      const symbol = cleanSym(b.symbol);
      const direction = String(b.direction || '').toUpperCase();
      if (!symbol || !['BUY', 'SELL'].includes(direction)) return res.status(200).json({ error: 'Analyse the symbol first.' });
      const count = Math.max(1, Math.min(50, parseInt(b.count, 10) || 1));
      const lot = Math.max(0.01, Math.min(100, Number(b.lot) || 0.01));
      const r = await execute(req, cfg, sub, s, {
        symbol, direction, lot, count, sl: Number(b.sl) || 0, tp: Number(b.tp) || 0,
        confidence: num(b.confidence), timeframes: Array.isArray(b.timeframes) ? b.timeframes.slice(0, 5).map(String) : []
      });
      return res.status(200).json(r);
    }

    return res.status(400).json({ error: 'Unknown action' });
  } catch (e) {
    return res.status(200).json({ error: String(e.message || e) });
  }
};
