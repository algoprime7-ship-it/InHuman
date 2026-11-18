// Accounts, credits and settings shared by /api/ask, /api/client and /api/admin. Not a public route.
const crypto = require('crypto');
const { dbReady, cmd, pipeline, getJSON, setJSON } = require('./_db');

// ---------------- defaults (the super admin can change all of these in /admin → Configurations) ----------------
const DEFAULT_CONFIG = {
  tiers: {
    starter: { name: 'Starter', price: 15, credits: 500, link: 'https://www.paypal.com/ncp/payment/ZJDDVS7X8MJPA' },
    pro: { name: 'Pro', price: 25, credits: 900, link: 'https://www.paypal.com/ncp/payment/9F7H53LAQL2BS' },
    executive: { name: 'Executive', price: 50, credits: 2000, link: 'https://www.paypal.com/ncp/payment/772T7A99AAC6J' },
    spro: { name: 'S Pro', price: 100, credits: 4500, link: 'https://www.paypal.com/ncp/payment/LRW557CHSRCB2' }
  },
  free: { messages: 50, credits: 100, lockHours: 7 },
  costs: {
    message: 1, action: 2.25, song: 5, speechPerSecond: 0.75,
    // extra credits on top of every message, per face (AETHER is the normal face)
    faces: { aether: 0, jarvis: 3, specter: 5, matrix: 7, ultron: 9, him: 11, sentinel: 13, friday: 15 }
  },
  keys: { gemini: '', claude: '', openai: '', github: '' },   // secret: never sent to apps
  bridge: { url: '', token: '' }                              // admin's MT5 bridge (url is shared with apps, token is not)
};
const TIER_KEYS = Object.keys(DEFAULT_CONFIG.tiers);

function merge(base, over) {
  const out = Array.isArray(base) ? base.slice() : { ...base };
  Object.keys(over || {}).forEach((k) => {
    if (over[k] && typeof over[k] === 'object' && !Array.isArray(over[k]) && base[k] && typeof base[k] === 'object') out[k] = merge(base[k], over[k]);
    else if (over[k] !== undefined) out[k] = over[k];
  });
  return out;
}

async function getConfig() {
  if (!dbReady()) return DEFAULT_CONFIG;
  try { return merge(DEFAULT_CONFIG, (await getJSON('config')) || {}); } catch (e) { return DEFAULT_CONFIG; }
}

async function saveConfig(cfg) {
  await setJSON('config', cfg);
  return cfg;
}

/** What apps may see: no secret keys, just which ones the admin has filled in */
function publicConfig(cfg) {
  return {
    tiers: cfg.tiers,
    free: cfg.free,
    costs: cfg.costs,
    adminHas: {
      gemini: !!cfg.keys.gemini, claude: !!cfg.keys.claude, openai: !!cfg.keys.openai, github: !!cfg.keys.github,
      bridge: !!cfg.bridge.url
    },
    bridgeUrl: cfg.bridge.url || ''
  };
}

// ---------------- dates (South Africa) ----------------
function today() {
  return new Date().toLocaleDateString('en-CA', { timeZone: 'Africa/Johannesburg' }); // YYYY-MM-DD
}

const rid = (n = 24) => crypto.randomBytes(n).toString('hex');
const cleanEmail = (e) => String(e || '').trim().toLowerCase();
const round2 = (n) => Math.round(n * 100) / 100;

// ---------------- clients ----------------
async function clientByToken(token) {
  if (!token || !dbReady()) return null;
  const id = await cmd('GET', `tok:${token}`);
  if (!id) return null;
  return getJSON(`client:${id}`);
}

async function saveClient(c) {
  await setJSON(`client:${c.id}`, c);
  return c;
}

async function allClients() {
  const ids = (await cmd('SMEMBERS', 'clients')) || [];
  if (!ids.length) return [];
  const rows = await cmd('MGET', ...ids.map((id) => `client:${id}`));
  return (rows || []).map((r) => { try { return JSON.parse(r); } catch (e) { return null; } }).filter(Boolean);
}

function publicClient(c) {
  if (!c) return null;
  return {
    id: c.id, email: c.email, first: c.first, last: c.last, createdAt: c.createdAt,
    plan: c.plan, balance: round2(c.balance || 0), pending: c.pending || null, totalPaid: c.totalPaid || 0
  };
}

// ---------------- credits ----------------
/** Who is using the app: a signed-up client, or just a phone/browser (free) */
async function subjectFor({ token, deviceId }) {
  const client = token ? await clientByToken(token) : null;
  if (client) return { key: `usage:c:${client.id}`, client };
  const dev = String(deviceId || '').replace(/[^a-zA-Z0-9-]/g, '').slice(0, 64) || 'anonymous';
  return { key: `usage:d:${dev}`, client: null };
}

function fmtLeft(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
}

function freshUsage(u, cfg) {
  const now = Date.now();
  let x = u || { day: today(), msgs: 0, credits: 0, lockedUntil: 0 };
  if (x.lockedUntil && now >= x.lockedUntil) x = { day: today(), msgs: 0, credits: 0, lockedUntil: 0 };   // lock over: refill
  if (!x.lockedUntil && x.day !== today()) x = { day: today(), msgs: 0, credits: 0, lockedUntil: 0 };   // new day: refill
  return x;
}

function snapshot(sub, usage, cfg) {
  const c = sub.client;
  const paid = c && c.plan && c.plan !== 'free';
  return {
    signedIn: !!c,
    plan: paid ? c.plan : 'free',
    planName: paid ? (cfg.tiers[c.plan] ? cfg.tiers[c.plan].name : c.plan) : 'Free',
    balance: paid ? round2(c.balance || 0) : 0,
    pending: c ? c.pending || null : null,
    free: {
      messagesUsed: usage.msgs, messagesLimit: cfg.free.messages,
      creditsUsed: round2(usage.credits), creditsLimit: cfg.free.credits,
      lockedUntil: usage.lockedUntil || 0
    }
  };
}

/** Can this person send another message / use more credits right now? */
async function check(sub, cfg) {
  if (!dbReady()) return { ok: true, usage: null };
  const c = sub.client;
  const usage = freshUsage(await getJSON(sub.key), cfg);
  if (c && c.plan && c.plan !== 'free') {
    if ((c.balance || 0) <= 0) {
      return { ok: false, usage: snapshot(sub, usage, cfg), message: `You have used all your ${cfg.tiers[c.plan] ? cfg.tiers[c.plan].name : ''} credits. Top up in Settings, Credits and billing.` };
    }
    return { ok: true, usage: snapshot(sub, usage, cfg) };
  }
  if (usage.lockedUntil) {
    return {
      ok: false,
      usage: snapshot(sub, usage, cfg),
      message: `Daily free credits reached. Come back in ${fmtLeft(usage.lockedUntil - Date.now())}, or upgrade in Settings, Credits and billing.`
    };
  }
  return { ok: true, usage: snapshot(sub, usage, cfg) };
}

/** Take credits. messages = text/voice messages, credits = actions, songs, speech. */
async function charge(sub, cfg, { messages = 0, credits = 0 }) {
  if (!dbReady()) return null;
  const c = sub.client;
  const usage = freshUsage(await getJSON(sub.key), cfg);
  if (c && c.plan && c.plan !== 'free') {
    const fresh = (await getJSON(`client:${c.id}`)) || c;
    fresh.balance = round2(Math.max(0, (fresh.balance || 0) - messages * cfg.costs.message - credits));
    await saveClient(fresh);
    sub.client = fresh;
    return snapshot(sub, usage, cfg);
  }
  usage.msgs += messages;
  usage.credits = round2(usage.credits + credits);
  if (usage.msgs >= cfg.free.messages || usage.credits >= cfg.free.credits) {
    usage.lockedUntil = Date.now() + cfg.free.lockHours * 3600 * 1000;
  }
  await setJSON(sub.key, usage, 8 * 24 * 3600);
  return snapshot(sub, usage, cfg);
}

/** Credits for a spoken reply: about 2.6 words per second of speech */
function speechCredits(text, cfg) {
  const words = String(text || '').replace(/\[(laugh|pause|hmph|sigh)\]/gi, ' ').trim().split(/\s+/).filter(Boolean).length;
  const seconds = Math.max(1, Math.ceil(words / 2.6));
  return round2(seconds * cfg.costs.speechPerSecond);
}

// Faces (modes). Ultron is for paid plans only.
const FACES = ['aether', 'jarvis', 'specter', 'matrix', 'ultron', 'him', 'sentinel', 'friday'];
const PAID_FACES = ['ultron'];
function cleanFace(face, sub) {
  face = String(face || 'aether').toLowerCase();
  if (!FACES.includes(face)) face = 'aether';
  const paid = sub && sub.client && sub.client.plan && sub.client.plan !== 'free';
  if (PAID_FACES.includes(face) && !paid && dbReady()) face = 'aether';
  return face;
}
function faceCredits(face, cfg) {
  const f = (cfg.costs && cfg.costs.faces) || {};
  return Number(f[face] || 0);
}

const SONG_ACTIONS = ['play_here', 'play_music', 'youtube_play', 'youtube_music_play', 'spotify_play'];
function actionCredits(action, cfg) {
  if (!action || !action.type) return 0;
  if (SONG_ACTIONS.includes(action.type)) return cfg.costs.song;
  if (action.type === 'music') return 0;
  return cfg.costs.action;
}

// ---------------- stats ----------------
async function visit(deviceId) {
  if (!dbReady() || !deviceId) return;
  const k = `visits:${today()}`;
  await pipeline([['SADD', k, String(deviceId).slice(0, 64)], ['EXPIRE', k, String(40 * 24 * 3600)]]);
}

async function addIncome(amount, kind) {
  const d = today();
  const k = kind === 'credits' ? 'cred' : 'sub';
  await pipeline([
    ['INCRBYFLOAT', `inc:${d}:${k}`, String(amount)],
    ['INCRBYFLOAT', `inc:all:${k}`, String(amount)]
  ]);
}

module.exports = {
  DEFAULT_CONFIG, TIER_KEYS, getConfig, saveConfig, publicConfig, today, rid, cleanEmail, round2,
  clientByToken, saveClient, allClients, publicClient, subjectFor, check, charge, snapshot,
  speechCredits, actionCredits, faceCredits, cleanFace, FACES, PAID_FACES, visit, addIncome, fmtLeft, merge
};
