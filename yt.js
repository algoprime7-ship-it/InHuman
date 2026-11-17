// YouTube search — Vercel Serverless Function (GET /api/yt?q=song+name&embed=1)
//
// Optional (most reliable): YT_API_KEY = YouTube Data API v3 key in Vercel env vars.
// Without it, public Invidious / Piped servers are used (free, sometimes slow or down).
//
// Response: { results: [ { videoId, title, channel } ] }

const INVIDIOUS = ['https://inv.nadeko.net', 'https://yewtu.be', 'https://invidious.nerdvpn.de', 'https://iv.ggtyler.dev'];
const PIPED = ['https://pipedapi.kavin.rocks', 'https://api.piped.private.coffee', 'https://pipedapi.adminforge.de'];

async function getJson(url, ms = 4000) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    const r = await fetch(url, { signal: ctl.signal });
    if (!r.ok) throw new Error('http ' + r.status);
    return await r.json();
  } finally {
    clearTimeout(t);
  }
}

function firstSuccess(promises) {
  return new Promise((resolve, reject) => {
    let left = promises.length;
    if (!left) return reject(new Error('none'));
    promises.forEach((p) => p.then(resolve, () => { if (--left === 0) reject(new Error('all failed')); }));
  });
}

const decode = (s) => String(s || '')
  .replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  const q = String((req.query && req.query.q) || '').trim().slice(0, 200);
  const embedOnly = String((req.query && req.query.embed) || '') === '1';
  if (!q) return res.status(400).json({ error: 'q is required', results: [] });
  const enc = encodeURIComponent(q);

  if (process.env.YT_API_KEY) {
    try {
      const d = await getJson(`https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5${embedOnly ? '&videoEmbeddable=true' : ''}&q=${enc}&key=${process.env.YT_API_KEY}`);
      const results = (d.items || []).map((i) => ({
        videoId: i.id.videoId,
        title: decode(i.snippet.title),
        channel: decode(i.snippet.channelTitle)
      }));
      if (results.length) return res.status(200).json({ results });
    } catch (e) { /* fall back to public servers */ }
  }

  const okLength = (s) => !s || (s > 45 && s < 1500);
  try {
    const results = await firstSuccess([
      ...INVIDIOUS.map((h) => getJson(`${h}/api/v1/search?q=${enc}&type=video`).then((arr) => {
        const out = (Array.isArray(arr) ? arr : [])
          .filter((v) => v.videoId && okLength(v.lengthSeconds))
          .slice(0, 5)
          .map((v) => ({ videoId: v.videoId, title: v.title, channel: v.author }));
        if (!out.length) throw new Error('empty');
        return out;
      })),
      ...PIPED.map((h) => getJson(`${h}/search?q=${enc}&filter=videos`).then((d) => {
        const out = ((d && d.items) || [])
          .filter((v) => v.url && v.url.includes('v=') && okLength(v.duration))
          .slice(0, 5)
          .map((v) => ({ videoId: v.url.split('v=')[1].split('&')[0], title: v.title, channel: v.uploaderName }));
        if (!out.length) throw new Error('empty');
        return out;
      }))
    ]);
    res.setHeader('Cache-Control', 's-maxage=3600');
    return res.status(200).json({ results });
  } catch (e) {
    return res.status(200).json({ results: [] });
  }
};
