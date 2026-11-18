// Tiny Upstash Redis client (REST, no packages needed). Not a public route.
//
// Connect it in Vercel: Project → Storage → Create Database → Upstash for Redis (free) → Connect.
// Vercel then adds KV_REST_API_URL + KV_REST_API_TOKEN (or UPSTASH_REDIS_REST_URL + _TOKEN) automatically.

const URL_ = () => process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL || '';
const TOKEN = () => process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN || '';

function dbReady() {
  return !!(URL_() && TOKEN());
}

async function cmd(...args) {
  if (!dbReady()) throw Object.assign(new Error('DB_NOT_CONNECTED'), { code: 'DB_NOT_CONNECTED' });
  const r = await fetch(URL_(), {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN()}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(args.map((a) => (typeof a === 'number' ? String(a) : a)))
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok || d.error) throw new Error(`Redis: ${d.error || r.status}`);
  return d.result;
}

async function pipeline(commands) {
  if (!commands.length) return [];
  if (!dbReady()) throw Object.assign(new Error('DB_NOT_CONNECTED'), { code: 'DB_NOT_CONNECTED' });
  const r = await fetch(URL_() + '/pipeline', {
    method: 'POST',
    headers: { Authorization: `Bearer ${TOKEN()}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(commands.map((c) => c.map((a) => (typeof a === 'number' ? String(a) : a))))
  });
  const d = await r.json().catch(() => []);
  if (!r.ok) throw new Error(`Redis pipeline: ${r.status}`);
  return d.map((x) => (x && x.error ? null : x && x.result));
}

async function getJSON(key) {
  const v = await cmd('GET', key);
  if (v == null) return null;
  try { return JSON.parse(v); } catch (e) { return null; }
}

async function setJSON(key, value, exSeconds) {
  const args = ['SET', key, JSON.stringify(value)];
  if (exSeconds) args.push('EX', String(exSeconds));
  return cmd(...args);
}

module.exports = { dbReady, cmd, pipeline, getJSON, setJSON };
