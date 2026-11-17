// Shared music search helpers (not a public route).
const INVIDIOUS = ['https://inv.nadeko.net', 'https://yewtu.be', 'https://invidious.nerdvpn.de', 'https://iv.ggtyler.dev'];
const PIPED = ['https://pipedapi.kavin.rocks', 'https://api.piped.private.coffee', 'https://pipedapi.adminforge.de'];

async function getJson(url, ms = 4500) {
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

async function searchYouTube(q, embedOnly = false) {
  const enc = encodeURIComponent(q);
  if (process.env.YT_API_KEY) {
    try {
      const d = await getJson(`https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5${embedOnly ? '&videoEmbeddable=true' : ''}&q=${enc}&key=${process.env.YT_API_KEY}`);
      const out = (d.items || []).map((i) => ({ videoId: i.id.videoId, title: decode(i.snippet.title), channel: decode(i.snippet.channelTitle) }));
      if (out.length) return out;
    } catch (e) { /* fall back */ }
  }
  const okLength = (s) => !s || (s > 45 && s < 1500);
  try {
    return await firstSuccess([
      ...INVIDIOUS.map((h) => getJson(`${h}/api/v1/search?q=${enc}&type=video`).then((arr) => {
        const out = (Array.isArray(arr) ? arr : []).filter((v) => v.videoId && okLength(v.lengthSeconds)).slice(0, 5)
          .map((v) => ({ videoId: v.videoId, title: v.title, channel: v.author }));
        if (!out.length) throw new Error('empty');
        return out;
      })),
      ...PIPED.map((h) => getJson(`${h}/search?q=${enc}&filter=videos`).then((d) => {
        const out = ((d && d.items) || []).filter((v) => v.url && v.url.includes('v=') && okLength(v.duration)).slice(0, 5)
          .map((v) => ({ videoId: v.url.split('v=')[1].split('&')[0], title: v.title, channel: v.uploaderName }));
        if (!out.length) throw new Error('empty');
        return out;
      }))
    ]);
  } catch (e) {
    return [];
  }
}

// Audius: free, full-length songs that can play in the background (no video needed)
async function searchAudius(q) {
  try {
    const hosts = await getJson('https://api.audius.co', 4000);
    const host = (hosts.data || [])[0];
    if (!host) return [];
    const d = await getJson(`${host}/v1/tracks/search?query=${encodeURIComponent(q)}&app_name=InHumanAI`, 5000);
    return (d.data || []).filter((t) => t.duration > 45).slice(0, 5).map((t) => ({
      title: t.title,
      artist: t.user && t.user.name,
      url: `${host}/v1/tracks/${t.id}/stream?app_name=InHumanAI`
    }));
  } catch (e) {
    return [];
  }
}

module.exports = { searchYouTube, searchAudius };
