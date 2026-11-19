// Cloned voices: the app sends each line here, this asks your voice server (droplet) to speak it
// in that face's voice and returns the MP3.
//   POST /api/tts { text, face }  →  audio/mpeg   (needs VOICE_SERVER_URL + VOICE_SERVER_KEY on Vercel)
const { cors, readBody } = require('./_ai');

const FACES = ['aether', 'jarvis', 'ultron', 'him', 'matrix', 'sentinel', 'specter', 'friday'];

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  const base = String(process.env.VOICE_SERVER_URL || '').trim().replace(/\/+$/, '');
  const key = String(process.env.VOICE_SERVER_KEY || '').trim();
  if (!base || !key) return res.status(503).json({ error: 'No voice server set' });
  const b = req.method === 'POST' ? readBody(req) : (req.query || {});
  const text = String(b.text || '').replace(/\[(laugh|sigh|hmph|pause)\]/gi, ' ').replace(/\s+/g, ' ').trim().slice(0, 600);
  const face = FACES.includes(b.face) ? b.face : 'aether';
  if (!text) return res.status(400).json({ error: 'No text' });
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 55000);
  try {
    const r = await fetch(`${base}/tts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Key': key },
      body: JSON.stringify({ text, face }),
      signal: ctrl.signal
    });
    if (!r.ok) return res.status(502).json({ error: `Voice server ${r.status}` });
    const buf = Buffer.from(await r.arrayBuffer());
    res.setHeader('Content-Type', 'audio/mpeg');
    res.setHeader('Cache-Control', 'public, max-age=86400');
    return res.status(200).send(buf);
  } catch (e) {
    return res.status(504).json({ error: e.name === 'AbortError' ? 'Voice server too slow' : 'Voice server offline' });
  } finally {
    clearTimeout(timer);
  }
};
