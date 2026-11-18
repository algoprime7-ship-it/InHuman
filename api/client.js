// App users — /api/client?action=...
//   GET  public            → tiers, prices, PayPal links, free limits, which keys the admin filled in
//   POST visit             { deviceId }                       counts a daily visit
//   GET  me                ?token=&deviceId=                  account + credits
//   POST signup            { email, first, last, deviceId }  free account (no password)
//   POST usage             { token, deviceId, kinds:['message','song','action'] }  check + take credits
//   POST pay               { token, tier }                    "I've paid" → waits for admin approval
//   GET  data              ?token=                            saved history / playlists / settings
//   POST save              { token, data }                    save them
const { dbReady, cmd, getJSON, setJSON } = require('./_db');
const B = require('./_billing');
const { cors, readBody } = require('./_ai');

const EMAIL_RE = /^[^\s@<>"']+@[^\s@<>"']+\.[^\s@<>"']+$/;

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  const q = req.query || {};
  const action = String(q.action || '');
  const b = req.method === 'POST' ? readBody(req) : {};
  const cfg = await B.getConfig();

  try {
    if (action === 'public') {
      return res.status(200).json({ ...B.publicConfig(cfg), database: dbReady() });
    }
    if (!dbReady()) return res.status(200).json({ error: 'Accounts are not switched on yet (database not connected).', database: false });

    if (action === 'visit') {
      await B.visit(b.deviceId);
      return res.status(200).json({ ok: true });
    }

    if (action === 'me') {
      const sub = await B.subjectFor({ token: q.token, deviceId: q.deviceId });
      const gate = await B.check(sub, cfg);
      return res.status(200).json({ client: B.publicClient(sub.client), usage: gate.usage, ok: gate.ok, message: gate.message || '' });
    }

    if (action === 'signup') {
      const email = B.cleanEmail(b.email);
      const first = String(b.first || '').trim().slice(0, 40);
      const last = String(b.last || '').trim().slice(0, 40);
      if (!EMAIL_RE.test(email) || !first || !last) return res.status(400).json({ error: 'Enter your email, first name and last name.' });
      if (await cmd('GET', `email:${email}`)) {
        return res.status(409).json({ error: 'This email already has an InHuman account. Contact support to move it to this device.' });
      }
      const id = B.rid(8);
      const token = B.rid(24);
      const client = {
        id, email, first, last, createdAt: Date.now(), plan: 'free', balance: 0, pending: null, totalPaid: 0,
        devices: b.deviceId ? [String(b.deviceId).slice(0, 64)] : []
      };
      await B.saveClient(client);
      await cmd('SET', `email:${email}`, id);
      await cmd('SET', `tok:${token}`, id);
      await cmd('SADD', 'clients', id);
      await cmd('INCR', `signups:${B.today()}`);
      // carry over what this device already used today, so signing up doesn't reset free limits
      if (b.deviceId) {
        const dev = await getJSON(`usage:d:${String(b.deviceId).replace(/[^a-zA-Z0-9-]/g, '').slice(0, 64)}`);
        if (dev) await setJSON(`usage:c:${id}`, dev, 8 * 24 * 3600);
      }
      const sub = { key: `usage:c:${id}`, client };
      const gate = await B.check(sub, cfg);
      return res.status(200).json({ token, client: B.publicClient(client), usage: gate.usage });
    }

    if (action === 'usage') {
      const kinds = Array.isArray(b.kinds) ? b.kinds : [b.kind];
      const sub = await B.subjectFor({ token: b.token, deviceId: b.deviceId });
      const gate = await B.check(sub, cfg);
      if (!gate.ok) return res.status(200).json({ ok: false, message: gate.message, usage: gate.usage });
      let messages = 0, credits = 0;
      kinds.forEach((k) => {
        if (k === 'message') messages += 1;
        else if (k === 'song') credits += cfg.costs.song;
        else if (k === 'action') credits += cfg.costs.action;
      });
      const usage = await B.charge(sub, cfg, { messages, credits });
      return res.status(200).json({ ok: true, usage });
    }

    if (action === 'pay') {
      const client = await B.clientByToken(b.token);
      if (!client) return res.status(401).json({ error: 'Create a free account first so we know who paid.' });
      const tier = String(b.tier || '');
      if (!cfg.tiers[tier]) return res.status(400).json({ error: 'Unknown plan.' });
      client.pending = { tier, credits: cfg.tiers[tier].credits, price: cfg.tiers[tier].price, at: Date.now() };
      await B.saveClient(client);
      await cmd('SADD', 'pending', client.id);
      return res.status(200).json({ ok: true, client: B.publicClient(client) });
    }

    if (action === 'data') {
      const client = await B.clientByToken(q.token);
      if (!client) return res.status(401).json({ error: 'Not signed in' });
      return res.status(200).json({ data: (await getJSON(`cdata:${client.id}`)) || null });
    }

    if (action === 'save') {
      const client = await B.clientByToken(b.token);
      if (!client) return res.status(401).json({ error: 'Not signed in' });
      const json = JSON.stringify(b.data || {});
      if (json.length > 400000) return res.status(413).json({ error: 'Too much data to save' });
      await setJSON(`cdata:${client.id}`, { ...(b.data || {}), savedAt: Date.now() });
      return res.status(200).json({ ok: true });
    }

    return res.status(400).json({ error: 'Unknown action' });
  } catch (e) {
    return res.status(500).json({ error: String(e.message || e) });
  }
};
