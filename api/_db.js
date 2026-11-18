// Tiny Redis client, no packages needed. Not a public route.
//
// Works with either database you add in Vercel → Storage:
//   • "Redis" (Redis Cloud, free 30 MB)   → Vercel adds REDIS_URL
//   • "Upstash for Redis"                 → Vercel adds KV_REST_API_URL + KV_REST_API_TOKEN
//                                           (or UPSTASH_REDIS_REST_URL + UPSTASH_REDIS_REST_TOKEN)
// Connect the database to the project, then redeploy.
const net = require('net');
const tls = require('tls');

const REST_URL = () => process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL || '';
const REST_TOKEN = () => process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN || '';
const TCP_URL = () => process.env.REDIS_URL || process.env.KV_URL || process.env.REDIS_TLS_URL || '';
const useRest = () => !!(REST_URL() && REST_TOKEN());

function dbReady() {
  return useRest() || !!TCP_URL();
}

const notConnected = () => Object.assign(new Error('DB_NOT_CONNECTED'), { code: 'DB_NOT_CONNECTED' });
const str = (a) => (typeof a === 'number' ? String(a) : a);

// ---------------- REST (Upstash) ----------------
async function restCmd(args) {
  const r = await fetch(REST_URL(), {
    method: 'POST',
    headers: { Authorization: `Bearer ${REST_TOKEN()}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(args.map(str))
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok || d.error) throw new Error(`Redis: ${d.error || r.status}`);
  return d.result;
}

async function restPipeline(commands) {
  const r = await fetch(REST_URL() + '/pipeline', {
    method: 'POST',
    headers: { Authorization: `Bearer ${REST_TOKEN()}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(commands.map((c) => c.map(str)))
  });
  const d = await r.json().catch(() => []);
  if (!r.ok) throw new Error(`Redis pipeline: ${r.status}`);
  return d.map((x) => (x && x.error ? null : x && x.result));
}

// ---------------- TCP (REDIS_URL), speaking the Redis protocol directly ----------------
let conn = null;

function encode(args) {
  const parts = [Buffer.from(`*${args.length}\r\n`)];
  args.forEach((a) => {
    const b = Buffer.from(String(str(a)));
    parts.push(Buffer.from(`$${b.length}\r\n`), b, Buffer.from('\r\n'));
  });
  return Buffer.concat(parts);
}

// Reads one reply from buf at pos. Returns [value, nextPos] or null if more data is needed.
function parseReply(buf, pos) {
  if (pos >= buf.length) return null;
  const end = buf.indexOf('\r\n', pos);
  if (end < 0) return null;
  const type = String.fromCharCode(buf[pos]);
  const line = buf.toString('utf8', pos + 1, end);
  const next = end + 2;
  if (type === '+') return [line, next];
  if (type === '-') return [Object.assign(new Error(line), { redisError: true }), next];
  if (type === ':') return [Number(line), next];
  if (type === '$') {
    const len = Number(line);
    if (len < 0) return [null, next];
    if (buf.length < next + len + 2) return null;
    return [buf.toString('utf8', next, next + len), next + len + 2];
  }
  if (type === '*') {
    const n = Number(line);
    if (n < 0) return [null, next];
    const out = [];
    let p = next;
    for (let i = 0; i < n; i++) {
      const r = parseReply(buf, p);
      if (!r) return null;
      out.push(r[0]);
      p = r[1];
    }
    return [out, p];
  }
  return [Object.assign(new Error('Redis: bad reply'), { redisError: true }), next];
}

function connect() {
  if (conn && !conn.closed) return conn.ready;
  const u = new URL(TCP_URL());
  const secure = u.protocol === 'rediss:';
  const port = Number(u.port) || 6379;
  const socket = secure
    ? tls.connect({ host: u.hostname, port, servername: u.hostname })
    : net.connect({ host: u.hostname, port });
  socket.setNoDelay(true);
  const c = { socket, buf: Buffer.alloc(0), queue: [], closed: false };
  const failAll = (err) => {
    c.closed = true;
    c.queue.splice(0).forEach((q) => q.reject(err));
    if (conn === c) conn = null;
  };
  socket.on('data', (chunk) => {
    c.buf = c.buf.length ? Buffer.concat([c.buf, chunk]) : chunk;
    let pos = 0;
    while (c.queue.length) {
      const r = parseReply(c.buf, pos);
      if (!r) break;
      pos = r[1];
      c.queue.shift().resolve(r[0]);
    }
    c.buf = c.buf.slice(pos);
  });
  socket.on('error', (e) => failAll(e));
  socket.on('close', () => failAll(new Error('Redis connection closed')));
  socket.setTimeout(15000, () => socket.destroy(new Error('Redis timeout')));
  const send = (args) => new Promise((resolve, reject) => {
    c.queue.push({ resolve, reject });
    socket.write(encode(args));
  });
  c.send = send;
  c.ready = new Promise((resolve, reject) => {
    socket.once(secure ? 'secureConnect' : 'connect', async () => {
      try {
        const user = decodeURIComponent(u.username || '');
        const pass = decodeURIComponent(u.password || '');
        if (pass) {
          const a = await send(user && user !== 'default' ? ['AUTH', user, pass] : ['AUTH', pass]);
          if (a instanceof Error) throw a;
        }
        const db = Number((u.pathname || '').replace('/', '')) || 0;
        if (db) await send(['SELECT', String(db)]);
        resolve(c);
      } catch (e) { reject(e); socket.destroy(); }
    });
    socket.once('error', reject);
  });
  conn = c;
  return c.ready;
}

async function tcpCmd(args) {
  const c = await connect();
  const v = await c.send(args);
  if (v instanceof Error) throw new Error(`Redis: ${v.message}`);
  return v;
}

async function tcpPipeline(commands) {
  const c = await connect();
  const out = await Promise.all(commands.map((cmdArgs) => c.send(cmdArgs)));
  return out.map((v) => (v instanceof Error ? null : v));
}

// ---------------- the same API for both ----------------
async function cmd(...args) {
  if (!dbReady()) throw notConnected();
  return useRest() ? restCmd(args) : tcpCmd(args);
}

async function pipeline(commands) {
  if (!commands.length) return [];
  if (!dbReady()) throw notConnected();
  return useRest() ? restPipeline(commands) : tcpPipeline(commands);
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
