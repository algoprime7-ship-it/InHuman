// Super admin — /api/admin?action=...
//   GET  status                         → is there a super admin yet? is the database connected?
//   POST signup  { email, first, last, phone, age, password, confirm }   (only works once: the first account)
//   POST login   { email, password }    → { token }
//   POST logout
//   GET  stats                          → dashboard numbers
//   GET  clients ?filter=all|paid|free|pending&q=search
//   POST markPaid { id, tier, credits, amount }   subscription paid → plan + credits
//   POST approve  { id, credits, amount }         approve a client's "I've paid" request
//   POST decline  { id }                          back to the free plan
//   GET  config                         → settings with secrets hidden
//   POST config  { ...changes }         → save (empty secret = keep the saved one, "-" = remove it)
//   GET  briefing                       → AI spoken summary for the dashboard
const crypto = require('crypto');
const { dbReady, cmd, pipeline, getJSON, setJSON } = require('./_db');
const B = require('./_billing');
const Mail = require('./_mail');
const { callAI, parseJsonReply, cors, readBody } = require('./_ai');

const SESSION_DAYS = 7;

function hashPassword(password, salt) {
  return crypto.scryptSync(String(password), salt, 64).toString('hex');
}

async function requireAdmin(req) {
  const auth = String((req.headers && (req.headers.authorization || req.headers.Authorization)) || '');
  const token = auth.replace(/^Bearer\s+/i, '').trim();
  if (!token || token.length < 20) return false;
  return !!(await cmd('GET', `asess:${token}`));
}

function maskConfig(cfg) {
  const dots = (v) => (v ? '••••••••' : '');
  return {
    tiers: cfg.tiers,
    free: cfg.free,
    costs: cfg.costs,
    keys: { gemini: dots(cfg.keys.gemini), claude: dots(cfg.keys.claude), openai: dots(cfg.keys.openai), github: dots(cfg.keys.github) },
    bridge: { url: dots(cfg.bridge.url), token: dots(cfg.bridge.token) },
    env: {
      gemini: !!process.env.GEMINI_API_KEY, claude: !!process.env.ANTHROPIC_API_KEY, openai: !!process.env.OPENAI_API_KEY
    }
  };
}

async function tradingSummary(cfg) {
  if (!cfg.bridge.url || !cfg.bridge.token) return null;
  try {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), 6000);
    const r = await fetch(`${cfg.bridge.url.replace(/\/+$/, '')}/account`, {
      headers: { Authorization: `Bearer ${cfg.bridge.token}`, 'X-Connection-Token': cfg.bridge.token },
      signal: ctl.signal
    });
    clearTimeout(t);
    const d = await r.json();
    const a = d.account || d.data || d;
    const num = (v) => (v == null || v === '' || isNaN(Number(v)) ? null : Number(v));
    return {
      balance: num(a.balance), equity: num(a.equity), profit: num(a.profit), currency: a.currency || 'USD'
    };
  } catch (e) {
    return { error: 'bridge unreachable' };
  }
}

async function getStats(cfg) {
  const clients = await B.allClients();
  const d = B.today();
  const days = [];
  for (let i = 6; i >= 0; i--) {
    days.push(new Date(Date.now() - i * 86400000).toLocaleDateString('en-CA', { timeZone: 'Africa/Johannesburg' }));
  }
  const res = await pipeline([
    ['GET', `signups:${d}`],
    ['SCARD', `visits:${d}`],
    ['GET', `inc:${d}:sub`], ['GET', `inc:${d}:cred`],
    ['GET', 'inc:all:sub'], ['GET', 'inc:all:cred'],
    ...days.map((x) => ['SCARD', `visits:${x}`]),
    ...days.map((x) => ['GET', `inc:${x}:sub`]),
    ...days.map((x) => ['GET', `inc:${x}:cred`])
  ]);
  const n = (v) => Number(v || 0);
  const perTier = {};
  B.TIER_KEYS.forEach((k) => { perTier[k] = 0; });
  let paid = 0, pending = 0;
  clients.forEach((c) => {
    if (c.plan && c.plan !== 'free') { paid++; perTier[c.plan] = (perTier[c.plan] || 0) + 1; }
    if (c.pending) pending++;
  });
  const v7 = res.slice(6, 13).map(n);
  const s7 = res.slice(13, 20).map(n);
  const c7 = res.slice(20, 27).map(n);
  return {
    totalClients: clients.length,
    paidClients: paid,
    freeClients: clients.length - paid,
    pendingClients: pending,
    perTier,
    tierNames: Object.fromEntries(B.TIER_KEYS.map((k) => [k, cfg.tiers[k].name])),
    signupsToday: n(res[0]),
    visitsToday: n(res[1]),
    incomeToday: { subscriptions: n(res[2]), credits: n(res[3]), total: B.round2(n(res[2]) + n(res[3])) },
    incomeAll: { subscriptions: n(res[4]), credits: n(res[5]), total: B.round2(n(res[4]) + n(res[5])) },
    week: days.map((day, i) => ({ day, visits: v7[i], income: B.round2(s7[i] + c7[i]) }))
  };
}

module.exports = async (req, res) => {
  cors(res);
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  if (req.method === 'OPTIONS') return res.status(204).end();
  const action = String((req.query && req.query.action) || '');
  const b = req.method === 'POST' ? readBody(req) : {};

  try {
    if (action === 'status') {
      if (!dbReady()) return res.status(200).json({ database: false, adminExists: false });
      return res.status(200).json({ database: true, adminExists: !!(await cmd('EXISTS', 'admin')) });
    }
    if (!dbReady()) return res.status(200).json({ error: 'Database not connected. In Vercel: Storage → Upstash for Redis → Connect, then redeploy.' });

    if (action === 'signup') {
      if (await cmd('EXISTS', 'admin')) return res.status(403).json({ error: 'The super admin account already exists. Please log in.' });
      const email = B.cleanEmail(b.email);
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.status(400).json({ error: 'Enter a valid email.' });
      if (!b.first || !b.last) return res.status(400).json({ error: 'Enter your first and last name.' });
      if (String(b.password || '').length < 8) return res.status(400).json({ error: 'Password must be at least 8 characters.' });
      if (b.password !== b.confirm) return res.status(400).json({ error: 'Passwords do not match.' });
      const salt = B.rid(16);
      const admin = {
        email, first: String(b.first).trim(), last: String(b.last).trim(),
        phone: String(b.phone || '').trim(), age: String(b.age || '').trim(),
        salt, hash: hashPassword(b.password, salt), createdAt: Date.now()
      };
      // NX: only the very first sign-up can ever create the super admin
      const ok = await cmd('SET', 'admin', JSON.stringify(admin), 'NX');
      if (ok !== 'OK') return res.status(403).json({ error: 'The super admin account already exists.' });
      const token = B.rid(32);
      await cmd('SET', `asess:${token}`, '1', 'EX', String(SESSION_DAYS * 86400));
      return res.status(200).json({ token, admin: { email, first: admin.first, last: admin.last } });
    }

    if (action === 'login') {
      const ip = String((req.headers && (req.headers['x-forwarded-for'] || '')) || 'x').split(',')[0].trim();
      const tries = await cmd('INCR', `alogin:${ip}`);
      if (tries === 1) await cmd('EXPIRE', `alogin:${ip}`, '600');
      if (tries > 8) return res.status(429).json({ error: 'Too many attempts. Wait 10 minutes.' });
      const admin = await getJSON('admin');
      if (!admin) return res.status(404).json({ error: 'No super admin yet. Sign up first.' });
      const good = B.cleanEmail(b.email) === admin.email &&
        crypto.timingSafeEqual(Buffer.from(hashPassword(b.password || '', admin.salt), 'hex'), Buffer.from(admin.hash, 'hex'));
      if (!good) return res.status(401).json({ error: 'Wrong email or password.' });
      await cmd('DEL', `alogin:${ip}`);
      const token = B.rid(32);
      await cmd('SET', `asess:${token}`, '1', 'EX', String(SESSION_DAYS * 86400));
      return res.status(200).json({ token, admin: { email: admin.email, first: admin.first, last: admin.last } });
    }

    // ---------- everything below needs a logged-in super admin ----------
    if (!(await requireAdmin(req))) return res.status(401).json({ error: 'Please log in again.' });
    const cfg = await B.getConfig();

    if (action === 'logout') {
      const token = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
      await cmd('DEL', `asess:${token}`);
      return res.status(200).json({ ok: true });
    }

    if (action === 'me') {
      const admin = await getJSON('admin');
      return res.status(200).json({ admin: { email: admin.email, first: admin.first, last: admin.last } });
    }

    if (action === 'stats') return res.status(200).json(await getStats(cfg));

    if (action === 'clients') {
      const filter = String(req.query.filter || 'all');
      const q = String(req.query.q || '').trim().toLowerCase();
      let list = await B.allClients();
      if (filter === 'paid') list = list.filter((c) => c.plan && c.plan !== 'free');
      if (filter === 'free') list = list.filter((c) => !c.plan || c.plan === 'free');
      if (filter === 'pending') list = list.filter((c) => c.pending);
      if (q) list = list.filter((c) => `${c.first} ${c.last} ${c.email}`.toLowerCase().includes(q));
      list.sort((a, b2) => (b2.pending ? 1 : 0) - (a.pending ? 1 : 0) || b2.createdAt - a.createdAt);
      return res.status(200).json({ clients: list.slice(0, 500).map(B.publicClient), tiers: cfg.tiers });
    }

    if (action === 'markPaid' || action === 'approve') {
      const c = await getJSON(`client:${b.id}`);
      if (!c) return res.status(404).json({ error: 'Client not found' });
      const tier = action === 'approve' ? (b.tier || (c.pending && c.pending.tier)) : b.tier;
      if (!cfg.tiers[tier]) return res.status(400).json({ error: 'Choose a plan' });
      const credits = Math.max(0, Number(b.credits != null ? b.credits : cfg.tiers[tier].credits) || 0);
      const amount = Math.max(0, Number(b.amount != null ? b.amount : cfg.tiers[tier].price) || 0);
      c.plan = tier;
      c.balance = B.round2((c.balance || 0) + credits);
      c.totalPaid = B.round2((c.totalPaid || 0) + amount);
      c.paidAt = Date.now();
      c.pending = null;
      await B.saveClient(c);
      await cmd('SREM', 'pending', c.id);
      if (amount > 0) await B.addIncome(amount, action === 'approve' ? 'credits' : 'subscription');
      await cmd('LPUSH', 'payments', JSON.stringify({ id: c.id, email: c.email, tier, credits, amount, kind: action, at: Date.now() }));
      await cmd('LTRIM', 'payments', '0', '499');
      const mail = await Mail.activated(req, c, cfg.tiers[tier].name, credits);   // "Subscription activated" email
      return res.status(200).json({ ok: true, client: B.publicClient(c), emailSent: !!mail.sent, emailError: mail.sent ? '' : (mail.error || '') });
    }

    if (action === 'decline') {
      const c = await getJSON(`client:${b.id}`);
      if (!c) return res.status(404).json({ error: 'Client not found' });
      c.plan = 'free';
      c.balance = 0;
      c.pending = null;
      await B.saveClient(c);
      await cmd('SREM', 'pending', c.id);
      return res.status(200).json({ ok: true, client: B.publicClient(c) });
    }

    if (action === 'config') {
      if (req.method === 'GET') return res.status(200).json(maskConfig(cfg));
      const next = JSON.parse(JSON.stringify(cfg));
      const secret = (cur, v) => (v === undefined || v === '' || v === '••••••••' ? cur : v === '-' ? '' : String(v).trim());
      if (b.keys) ['gemini', 'claude', 'openai', 'github'].forEach((k) => { next.keys[k] = secret(next.keys[k], b.keys[k]); });
      if (b.bridge) {
        next.bridge.url = secret(next.bridge.url, b.bridge.url).replace(/\/+$/, '');
        next.bridge.token = secret(next.bridge.token, b.bridge.token);
      }
      if (b.tiers) B.TIER_KEYS.forEach((k) => {
        const t = b.tiers[k];
        if (!t) return;
        if (t.name) next.tiers[k].name = String(t.name).slice(0, 30);
        if (t.price !== undefined && !isNaN(Number(t.price))) next.tiers[k].price = Number(t.price);
        if (t.credits !== undefined && !isNaN(Number(t.credits))) next.tiers[k].credits = Number(t.credits);
        if (t.link && /^https:\/\//.test(t.link)) next.tiers[k].link = String(t.link).trim();
      });
      if (b.free) ['messages', 'credits', 'lockHours'].forEach((k) => {
        if (b.free[k] !== undefined && !isNaN(Number(b.free[k]))) next.free[k] = Number(b.free[k]);
      });
      if (b.costs) ['message', 'action', 'song', 'speechPerSecond', 'analysis', 'trade'].forEach((k) => {
        if (b.costs[k] !== undefined && !isNaN(Number(b.costs[k]))) next.costs[k] = Number(b.costs[k]);
      });
      await B.saveConfig(next);
      return res.status(200).json(maskConfig(next));
    }

    if (action === 'briefing') {
      const stats = await getStats(cfg);
      const trading = await tradingSummary(cfg);
      const admin = await getJSON('admin');
      const hour = Number(new Date().toLocaleString('en-GB', { timeZone: 'Africa/Johannesburg', hour: '2-digit', hour12: false }));
      const greet = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';
      const tierText = B.TIER_KEYS.filter((k) => stats.perTier[k]).map((k) => `${stats.perTier[k]} on ${cfg.tiers[k].name}`).join(', ');
      const money = (v) => `$${Number(v || 0).toFixed(2)}`;
      let text = `${greet}, sir. Today we have ${stats.signupsToday} new sign ups and ${stats.visitsToday} visits. ` +
        `In total there are ${stats.totalClients} clients. ` +
        (stats.paidClients ? `${stats.paidClients} have paid for their tier: ${tierText}. ` : 'No clients are on a paid tier yet. ') +
        (stats.pendingClients ? `${stats.pendingClients} payment${stats.pendingClients > 1 ? 's are' : ' is'} waiting for your approval. ` : '') +
        `Today we have made ${money(stats.incomeToday.total)} on PayPal: ${money(stats.incomeToday.subscriptions)} from app subscriptions and ${money(stats.incomeToday.credits)} from credit buyers. ` +
        (trading && !trading.error && trading.profit != null ? `On your trading account, the current profit is ${trading.profit.toFixed(2)} ${trading.currency}${trading.equity != null ? `, with equity at ${trading.equity.toFixed(2)}` : ''}.` : '');
      // let the AI say it naturally (falls back to the plain version above)
      try {
        const out = await callAI({
          system: 'You are AETHER, a loyal British AI butler briefing your creator, the super admin of the InHuman AI app. ' +
            'Rewrite the briefing below as 3 to 5 natural spoken sentences. Keep every number exactly as given. No markdown, no lists. Call him sir.' +
            (admin && admin.first ? ` His name is ${admin.first}.` : ''),
          history: [],
          message: text,
          keys: { ...cfg.keys },
          search: false,
          maxTokens: 400
        });
        const asJson = parseJsonReply(out);
        const said = asJson && typeof asJson.reply === 'string' ? asJson.reply : out;
        if (said && said.trim().length > 40) text = said.replace(/[*#`]/g, '').trim();
      } catch (e) { /* keep plain version */ }
      return res.status(200).json({ text, stats, trading });
    }

    return res.status(400).json({ error: 'Unknown action' });
  } catch (e) {
    return res.status(500).json({ error: String(e.message || e) });
  }
};
