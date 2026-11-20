// Connected InHuman users: invites, friends, messages, shared location, controlling a friend's devices.
//   POST /api/social?action=...   (all need { token } except invite_info / invite_accept)
//   invite {email}                    send the "Approve invite" email
//   invite_info {code}                who invited whom (for the approve card)
//   invite_accept {code, deviceId, token?}   connects both users; signs the invited person in if needed
//   friends                           connected users: name / nickname, picture, online devices, location, last message, unread
//   nick {friendId, name}             your own name for them (like a contact name)
//   remove {friendId}
//   avatar {data}                     your profile picture (small JPEG data URL)
//   location {lat, lng, acc, address, share}
//   send {to, text}                   message to a connected user
//   chat {with, before?}              the conversation (marks it read)
//   poll {deviceId}                   unread count + new messages for this device (to show / read aloud)
//   fcommand {friendId, target, command, arg}   play / stop / lock / wake / say on a friend's device
const { cors, readBody } = require('./_ai');
const { dbReady, cmd, pipeline, getJSON, setJSON } = require('./_db');
const B = require('./_billing');
const Mail = require('./_mail');

const pairKey = (a, b) => (a < b ? `${a}:${b}` : `${b}:${a}`);
const fullName = (c) => [c.first, c.last].filter(Boolean).join(' ') || (c.email || '').split('@')[0];
const clean = (v, n) => String(v || '').replace(/\s+/g, ' ').trim().slice(0, n);

async function friendIds(id) { return ((await cmd('SMEMBERS', `friends:${id}`)) || []).map(String); }
async function isFriend(a, b) { return Number(await cmd('SISMEMBER', `friends:${a}`, b)) === 1; }

async function devicesOf(id) {
  const h = (await cmd('HGETALL', `devs:${id}`)) || [];
  const pairs = Array.isArray(h) ? h : Object.entries(h).flat();
  const out = [];
  for (let i = 0; i < pairs.length; i += 2) { try { out.push(JSON.parse(pairs[i + 1])); } catch (e) {} }
  const now = Date.now();
  return out.map((d) => ({ id: d.id, name: d.name, platform: d.platform, screenOn: d.screenOn, battery: d.battery, canLock: d.canLock, lastSeen: d.lastSeen,
    online: now - (d.lastSeen || 0) < (d.platform === 'android-app' ? 150000 : 60000) })).sort((x, y) => (y.lastSeen || 0) - (x.lastSeen || 0));
}

async function findOrCreateClient(email, first) {
  let id = await cmd('GET', `email:${email}`);
  let client = id ? await getJSON(`client:${id}`) : null;
  if (client) return { client, created: false };
  id = B.rid(8);
  client = { id, email, first: clean(first || email.split('@')[0], 40), last: '', createdAt: Date.now(), plan: 'free', balance: 0, pending: null, totalPaid: 0, devices: [] };
  await cmd('SET', `email:${email}`, id);
  await cmd('SADD', 'clients', id);
  await cmd('INCR', `signups:${B.today()}`);
  await B.saveClient(client);
  return { client, created: true };
}

async function lastMessage(a, b) {
  const raw = await cmd('LINDEX', `chat:${pairKey(a, b)}`, '-1');
  try { return raw ? JSON.parse(raw) : null; } catch (e) { return null; }
}

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  const action = String((req.query && req.query.action) || '');
  try {
    if (!dbReady()) return res.status(200).json({ error: 'The database is not connected yet.' });
    const b = req.method === 'POST' ? readBody(req) : (req.query || {});

    // ---------- invites (no account needed to look / accept) ----------
    if (action === 'invite_info') {
      const inv = await getJSON(`invite:${clean(b.code, 64)}`);
      if (!inv) return res.status(200).json({ error: 'This invite has expired or was already used.' });
      const from = await getJSON(`client:${inv.from}`);
      return res.status(200).json({ from: from ? fullName(from) : 'Someone', fromFirst: from ? from.first : '', email: inv.email });
    }
    if (action === 'invite_accept') {
      const code = clean(b.code, 64);
      const inv = await getJSON(`invite:${code}`);
      if (!inv) return res.status(200).json({ error: 'This invite has expired or was already used.' });
      const from = await getJSON(`client:${inv.from}`);
      if (!from) return res.status(200).json({ error: 'The person who invited you no longer has an account.' });
      let me = b.token ? await B.clientByToken(b.token) : null;
      let token = b.token || '';
      if (!me) {
        // the invite link went to their email, so it signs them in as that email
        const r = await findOrCreateClient(B.cleanEmail(inv.email), '');
        me = r.client;
        token = B.rid(24);
        await cmd('SET', `tok:${token}`, me.id);
        if (r.created) await Mail.welcome(req, me);
      }
      if (me.id === from.id) return res.status(200).json({ error: "You can't connect to yourself." });
      if (b.deviceId) { me.devices = Array.from(new Set([...(me.devices || []), clean(b.deviceId, 64)])).slice(-20); await B.saveClient(me); }
      await pipeline([['SADD', `friends:${me.id}`, from.id], ['SADD', `friends:${from.id}`, me.id], ['DEL', `invite:${code}`]]);
      // tell the inviter
      await cmd('RPUSH', `notes:${from.id}`, JSON.stringify({ kind: 'connected', name: fullName(me), at: Date.now() }));
      await cmd('EXPIRE', `notes:${from.id}`, '604800');
      return res.status(200).json({ ok: true, token, client: B.publicClient(me), friend: { id: from.id, name: fullName(from) } });
    }

    const me = await B.clientByToken(b.token);
    if (!me) return res.status(200).json({ error: 'Sign in first.', signIn: true });

    if (action === 'invite') {
      const email = B.cleanEmail(b.email);
      if (!email || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return res.status(200).json({ error: 'Enter a valid email address.' });
      if (email === me.email) return res.status(200).json({ error: "That's your own email." });
      const existing = await cmd('GET', `email:${email}`);
      if (existing && await isFriend(me.id, existing)) return res.status(200).json({ error: 'You are already connected to that user.' });
      const code = B.rid(16);
      await setJSON(`invite:${code}`, { from: me.id, email, at: Date.now() }, 14 * 24 * 3600);
      const mail = await Mail.invite(req, me, email, code);
      return res.status(200).json({ ok: true, emailSent: !!(mail && mail.sent), emailError: mail && !mail.sent ? mail.error : '', link: `${Mail.siteUrl(req)}/?invite=${code}` });
    }

    if (action === 'friends') {
      const ids = await friendIds(me.id);
      const nicks = await cmd('HGETALL', `nick:${me.id}`) || [];
      const nickMap = {};
      const np = Array.isArray(nicks) ? nicks : Object.entries(nicks).flat();
      for (let i = 0; i < np.length; i += 2) nickMap[np[i]] = np[i + 1];
      const myLoc = await getJSON(`loc:${me.id}`);
      const unread = await cmd('HGETALL', `unread:${me.id}`) || [];
      const um = {};
      const up = Array.isArray(unread) ? unread : Object.entries(unread).flat();
      for (let i = 0; i < up.length; i += 2) um[up[i]] = Number(up[i + 1]) || 0;
      const out = [];
      for (const id of ids) {
        const f = await getJSON(`client:${id}`);
        if (!f) continue;
        const loc = await getJSON(`loc:${id}`);
        const avatar = await cmd('GET', `avatar:${id}`);
        out.push({
          id, email: f.email, first: f.first, last: f.last, realName: fullName(f), name: nickMap[id] || fullName(f), nick: nickMap[id] || '',
          avatar: avatar || '', devices: await devicesOf(id),
          location: loc && loc.share && myLoc && myLoc.share ? loc : null,     // only when both share
          last: await lastMessage(me.id, id), unread: um[id] || 0
        });
      }
      out.sort((x, y) => ((y.last && y.last.at) || 0) - ((x.last && x.last.at) || 0));
      const notes = (await cmd('LRANGE', `notes:${me.id}`, '0', '20')) || [];
      if (notes.length) await cmd('DEL', `notes:${me.id}`);
      return res.status(200).json({ friends: out, me: { id: me.id, name: fullName(me), avatar: (await cmd('GET', `avatar:${me.id}`)) || '', location: myLoc || null },
        notes: notes.map((x) => { try { return JSON.parse(x); } catch (e) { return null; } }).filter(Boolean) });
    }

    if (action === 'nick') {
      const fid = clean(b.friendId, 40);
      if (!(await isFriend(me.id, fid))) return res.status(200).json({ error: 'Not connected' });
      const name = clean(b.name, 40);
      if (name) await cmd('HSET', `nick:${me.id}`, fid, name); else await cmd('HDEL', `nick:${me.id}`, fid);
      return res.status(200).json({ ok: true });
    }

    if (action === 'remove') {
      const fid = clean(b.friendId, 40);
      await pipeline([['SREM', `friends:${me.id}`, fid], ['SREM', `friends:${fid}`, me.id]]);
      return res.status(200).json({ ok: true });
    }

    if (action === 'avatar') {
      const data = String(b.data || '');
      if (!/^data:image\/(jpeg|png|webp);base64,/.test(data) || data.length > 120000) return res.status(200).json({ error: 'Picture too big or not an image' });
      await cmd('SET', `avatar:${me.id}`, data);
      return res.status(200).json({ ok: true });
    }

    if (action === 'location') {
      const lat = Number(b.lat), lng = Number(b.lng);
      if (b.share === false) { await setJSON(`loc:${me.id}`, { share: false, at: Date.now() }); return res.status(200).json({ ok: true }); }
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) return res.status(200).json({ error: 'No location' });
      await setJSON(`loc:${me.id}`, { lat: Math.round(lat * 1e5) / 1e5, lng: Math.round(lng * 1e5) / 1e5, acc: Math.round(Number(b.acc) || 0), address: clean(b.address, 160), share: true, at: Date.now() }, 3 * 24 * 3600);
      return res.status(200).json({ ok: true });
    }

    if (action === 'send') {
      const to = clean(b.to, 40);
      const text = String(b.text || '').trim().slice(0, 2000);
      if (!text) return res.status(200).json({ error: 'Empty message' });
      if (!(await isFriend(me.id, to))) return res.status(200).json({ error: 'You can only message connected users.' });
      const msg = { id: B.rid(6), from: me.id, to, text, at: Date.now() };
      const key = `chat:${pairKey(me.id, to)}`;
      const devs = await devicesOf(to);
      const cmds = [['RPUSH', key, JSON.stringify(msg)], ['LTRIM', key, '-800', '-1'], ['HINCRBY', `unread:${to}`, me.id, '1'],
        ['SET', `chatread:${pairKey(me.id, to)}:${me.id}`, String(msg.at)]];
      const note = JSON.stringify({ ...msg, fromName: fullName(me) });
      devs.forEach((d) => { cmds.push(['RPUSH', `msgq:${to}:${d.id}`, note], ['LTRIM', `msgq:${to}:${d.id}`, '-30', '-1'], ['EXPIRE', `msgq:${to}:${d.id}`, '86400']); });
      await pipeline(cmds);
      return res.status(200).json({ ok: true, message: msg });
    }

    if (action === 'chat') {
      const other = clean(b.with, 40);
      if (!(await isFriend(me.id, other))) return res.status(200).json({ error: 'Not connected' });
      const key = `chat:${pairKey(me.id, other)}`;
      const all = ((await cmd('LRANGE', key, '-200', '-1')) || []).map((x) => { try { return JSON.parse(x); } catch (e) { return null; } }).filter(Boolean);
      const now = Date.now();
      await pipeline([['SET', `chatread:${pairKey(me.id, other)}:${me.id}`, String(now)], ['HDEL', `unread:${me.id}`, other]]);
      const otherReadAt = Number(await cmd('GET', `chatread:${pairKey(me.id, other)}:${other}`)) || 0;
      return res.status(200).json({ messages: all, otherReadAt, now });
    }

    if (action === 'poll') {
      const dev = clean(b.deviceId, 64).replace(/[^a-zA-Z0-9-]/g, '');
      const qk = `msgq:${me.id}:${dev}`;
      const got = await pipeline([['LRANGE', qk, '0', '30'], ['DEL', qk], ['HGETALL', `unread:${me.id}`]]);
      const fresh = ((got && got[0]) || []).map((x) => { try { return JSON.parse(x); } catch (e) { return null; } }).filter(Boolean);
      const up = (got && got[2]) || [];
      const arr = Array.isArray(up) ? up : Object.entries(up).flat();
      let total = 0;
      for (let i = 1; i < arr.length; i += 2) total += Number(arr[i]) || 0;
      // use the reader's own names for people
      const nicks = await cmd('HGETALL', `nick:${me.id}`) || [];
      const np = Array.isArray(nicks) ? nicks : Object.entries(nicks).flat();
      const nm = {};
      for (let i = 0; i < np.length; i += 2) nm[np[i]] = np[i + 1];
      fresh.forEach((m) => { if (nm[m.from]) m.fromName = nm[m.from]; });
      return res.status(200).json({ unread: total, messages: fresh });
    }

    if (action === 'fcommand') {
      const fid = clean(b.friendId, 40);
      if (!(await isFriend(me.id, fid))) return res.status(200).json({ error: 'Not connected' });
      const c = { command: clean(b.command, 20), arg: clean(b.arg, 200), from: fullName(me), at: Date.now() };
      if (!['play', 'stop', 'lock', 'wake', 'say'].includes(c.command)) return res.status(200).json({ error: 'Unknown command' });
      const devs = await devicesOf(fid);
      if (!devs.length) return res.status(200).json({ error: 'They have no devices signed in.' });
      const want = String(b.target || '').toLowerCase();
      const dev = devs.find((d) => d.id === b.target) || devs.find((d) => want && `${d.name} ${d.platform}`.toLowerCase().includes(want)) || devs.find((d) => d.online) || devs[0];
      const qk = `cmdq:${fid}:${dev.id}`;
      await pipeline([['RPUSH', qk, JSON.stringify(c)], ['EXPIRE', qk, '900']]);
      return res.status(200).json({ ok: true, device: dev });
    }

    return res.status(400).json({ error: 'Unknown action' });
  } catch (e) {
    return res.status(200).json({ error: String(e.message || e) });
  }
};
