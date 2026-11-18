// MT5 bridge relay for the web / iPhone app — POST /api/mt5
// Body: { bridge:"https://your-bridge", path:"connect"|"ordersend"|"account", token:"", body:{...} }
// The browser can't call most bridges directly (blocked by CORS), so the server passes the request on.
// Nothing is stored here: the MT5 password only goes to your bridge's /connect.
const { cors, readBody } = require('./_ai');

const PATHS = new Set(['connect', 'ordersend', 'account']);

function blockedHost(h) {
  h = String(h || '').toLowerCase();
  return h === 'localhost' || h.endsWith('.local') || h.endsWith('.internal') ||
    /^(127\.|10\.|192\.168\.|169\.254\.|0\.)/.test(h) || /^172\.(1[6-9]|2\d|3[01])\./.test(h) || h === '[::1]' || h.includes('metadata');
}

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });
  const b = readBody(req);
  const path = String(b.path || '');
  if (!PATHS.has(path)) return res.status(400).json({ error: 'Unknown bridge action' });
  let url;
  try { url = new URL(String(b.bridge || '').replace(/\/+$/, '') + '/' + path); } catch (e) { return res.status(400).json({ error: 'Bad bridge link' }); }
  if (!/^https?:$/.test(url.protocol) || blockedHost(url.hostname)) return res.status(400).json({ error: 'Bad bridge link' });

  const headers = { 'Content-Type': 'application/json' };
  if (b.token) { headers.Authorization = `Bearer ${b.token}`; headers['X-Connection-Token'] = String(b.token); }
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 18000);
  try {
    const r = await fetch(url.toString(), {
      method: path === 'account' ? 'GET' : 'POST',
      headers,
      body: path === 'account' ? undefined : JSON.stringify(b.body || {}),
      signal: ctrl.signal
    });
    const text = await r.text();
    let data;
    try { data = JSON.parse(text); } catch (e) { data = { error: text.slice(0, 200) }; }
    return res.status(200).json({ status: r.status, data });
  } catch (e) {
    return res.status(200).json({ status: 0, data: { error: 'Could not reach the bridge' } });
  } finally {
    clearTimeout(timer);
  }
};
