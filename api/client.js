// App users — /api/client?action=...
//   GET  public            → tiers, prices, PayPal links, free limits, which keys the admin filled in
//   POST visit             { deviceId }                       counts a daily visit
//   GET  me                ?token=&deviceId=                  account + credits
//   POST signup            { email, first, last, deviceId }  free account (no password)
//   POST usage             { token, deviceId, kinds:['message','song','action'] }  check + take credits
//   POST pay               { token, tier }                    "I've paid" → waits for admin approval
//   GET  data              ?token=                            saved history / playlists / settings
//   POST save              { token, data }                    save them
const { dbReady, cmd, pipeline, getJSON, setJSON } = require('./_db');
const B = require('./_billing');
const Mail = require('./_mail');
const { cors, readBody } = require('./_ai');

const EMAIL_RE = /^[^\s@<>"']+@[^\s@<>"']+\.[^\s@<>"']+$/;

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  const q = req.query || {};
  const action = String(q.action || '');
  let b = {}, cfg;
  try {
    b = req.method === 'POST' ? readBody(req) : {};
    cfg = await B.getConfig();
    if (action === 'ping') {
      // open /api/client?action=ping to test the database (shows only names, never values)
      const names = Object.keys(process.env).filter((k) => /REDIS|KV_|UPSTASH/.test(k));
      let test = 'not tried';
      if (dbReady()) {
        try { await cmd('SET', 'ping', String(Date.now())); test = 'OK: ' + (await cmd('GET', 'ping')); }
        catch (e) { test = 'FAILED: ' + String(e.message || e); }
      }
      return res.status(200).json({ database: dbReady(), test, variables: names, smtp: !!(process.env.SMTP_USER && process.env.SMTP_PASS) });
    }
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
      const existingId = await cmd('GET', `email:${email}`);
      if (existingId) {
        // already signed up: the same form signs them in on this device (names must match)
        const old = await getJSON(`client:${existingId}`);
        if (!old) return res.status(404).json({ error: 'Account not found. Contact support.' });
        const same = (x, y) => String(x || '').trim().toLowerCase() === String(y || '').trim().toLowerCase();
        if (!same(old.first, first) || !same(old.last, last)) {
          return res.status(403).json({ error: 'This email is already signed up with a different name. Type your first and last name exactly as you signed up.' });
        }
        const token = B.rid(24);
        await cmd('SET', `tok:${token}`, old.id);
        if (b.deviceId) {
          const dev = String(b.deviceId).slice(0, 64);
          old.devices = Array.from(new Set([...(old.devices || []), dev])).slice(-20);
          await B.saveClient(old);
        }
        const sub = { key: `usage:c:${old.id}`, client: old };
        const gate = await B.check(sub, cfg);
        return res.status(200).json({ token, client: B.publicClient(old), usage: gate.usage, login: true });
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
      const mail = await Mail.welcome(req, client);   // "Welcome to InHuman" email (skipped if SMTP isn't set)
      return res.status(200).json({ token, client: B.publicClient(client), usage: gate.usage, emailSent: !!mail.sent });
    }

    if (action === 'usage') {
      const kinds = Array.isArray(b.kinds) ? b.kinds : [b.kind];
      const sub = await B.subjectFor({ token: b.token, deviceId: b.deviceId });
      const gate = await B.check(sub, cfg);
      if (!gate.ok) return res.status(200).json({ ok: false, message: gate.message, usage: gate.usage });
      let messages = 0, credits = 0;
      const face = B.cleanFace(b.face, sub);
      kinds.forEach((k) => {
        if (k === 'message') credits += B.faceCredits(face, cfg);
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

    // ---------------- devices on the same account: status + remote commands ----------------
    //   POST heartbeat { token, deviceId, name, platform, model, screenOn, battery, charging, canLock, app }
    //        → saves this device, returns commands waiting for it
    //   POST devices   { token }                         → every device on the account
    //   POST command   { token, target, command, arg, from }   command: lock | wake | play | stop | open | say
    //   POST rename    { token, deviceId, name }
    if (['heartbeat', 'devices', 'command', 'rename', 'forget'].includes(action)) {
      const client = await B.clientByToken(b.token);
      if (!client) return res.status(200).json({ error: 'Sign in to link your devices.', devices: [] });
      const hk = `devs:${client.id}`;
      const dev = String(b.deviceId || '').replace(/[^a-zA-Z0-9-]/g, '').slice(0, 64);
      const list = async () => {
        const h = (await cmd('HGETALL', hk)) || [];
        const out = [];
        const pairs = Array.isArray(h) ? h : Object.entries(h).flat();
        for (let i = 0; i < pairs.length; i += 2) { try { out.push(JSON.parse(pairs[i + 1])); } catch (e) {} }
        const now = Date.now();
        return out.map((d) => ({ ...d, online: now - (d.lastSeen || 0) < (d.platform === 'android-app' ? 150000 : 60000) }))
          .sort((x, y) => (y.lastSeen || 0) - (x.lastSeen || 0));
      };
      if (action === 'heartbeat') {
        if (!dev) return res.status(400).json({ error: 'deviceId is required' });
        const old = JSON.parse((await cmd('HGET', hk, dev)) || 'null') || {};
        const d = {
          id: dev,
          name: String(old.custom ? old.name : (b.name || old.name || 'Device')).slice(0, 40),
          custom: !!old.custom,
          platform: String(b.platform || old.platform || 'web').slice(0, 20),
          model: String(b.model || old.model || '').slice(0, 60),
          screenOn: b.screenOn === undefined ? true : !!b.screenOn,
          battery: b.battery == null ? null : Math.round(Number(b.battery)),
          charging: !!b.charging,
          canLock: !!b.canLock,
          app: String(b.app || '').slice(0, 20),
          lastSeen: Date.now()
        };
        const qk = `cmdq:${client.id}:${dev}`;
        const got = await pipeline([['HSET', hk, dev, JSON.stringify(d)], ['LRANGE', qk, '0', '20'], ['DEL', qk]]);
        const commands = ((got && got[1]) || []).map((x) => { try { return JSON.parse(x); } catch (e) { return null; } })
          .filter((c) => c && Date.now() - c.at < 10 * 60000);
        return res.status(200).json({ ok: true, commands, device: d });
      }
      if (action === 'devices') return res.status(200).json({ devices: await list() });
      if (action === 'rename') {
        const old = JSON.parse((await cmd('HGET', hk, dev)) || 'null');
        if (!old) return res.status(200).json({ error: 'Device not found' });
        old.name = String(b.name || '').trim().slice(0, 40) || old.name;
        old.custom = true;
        await cmd('HSET', hk, dev, JSON.stringify(old));
        return res.status(200).json({ ok: true, devices: await list() });
      }
      if (action === 'forget') {
        await cmd('HDEL', hk, dev);
        return res.status(200).json({ ok: true, devices: await list() });
      }
      // command: target = device id, or a spoken name like "my iphone", "samsung", "tablet"
      const all = await list();
      const want = String(b.target || '').toLowerCase().replace(/\b(my|the|on|phone's)\b/g, ' ').replace(/\s+/g, ' ').trim();
      const score = (d) => {
        if (d.id === b.target) return 100;
        const hay = `${d.name} ${d.model} ${d.platform}`.toLowerCase();
        let sc = 0;
        want.split(' ').filter(Boolean).forEach((w) => {
          if (hay.includes(w)) sc += 3;
          if (w === 'iphone' && /iphone|ios/.test(hay)) sc += 3;
          if (w === 'ipad' && /ipad/.test(hay)) sc += 3;
          if (/^(android|samsung|galaxy)$/.test(w) && /android/.test(hay)) sc += 2;
          if (/^(phone|cell|mobile)$/.test(w) && /iphone|android|phone/.test(hay)) sc += 1;
          if (/^(laptop|computer|pc|desktop|mac)$/.test(w) && /windows|mac|linux|desktop|computer/.test(hay)) sc += 2;
        });
        return sc;
      };
      const ranked = all.filter((d) => d.id !== dev || b.allowSelf).map((d) => [score(d), d]).sort((x, y) => y[0] - x[0] || (y[1].lastSeen || 0) - (x[1].lastSeen || 0));
      if (!ranked.length) return res.status(200).json({ error: 'No other device is linked to your account yet. Sign in on it first.' });
      const best = ranked[0][0] > 0 || !want || ranked.length === 1 ? ranked[0][1] : null;
      if (!best) return res.status(200).json({ error: `I couldn't find a device called "${b.target}".`, devices: all.map((d) => d.name) });
      const c = { command: String(b.command || '').slice(0, 20), arg: String(b.arg || '').slice(0, 200), from: String(b.from || '').slice(0, 40), at: Date.now() };
      if (!['lock', 'wake', 'play', 'stop', 'open', 'say'].includes(c.command)) return res.status(200).json({ error: 'Unknown device command' });
      const qk = `cmdq:${client.id}:${best.id}`;
      await pipeline([['RPUSH', qk, JSON.stringify(c)], ['EXPIRE', qk, '900']]);
      return res.status(200).json({ ok: true, device: best });
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
