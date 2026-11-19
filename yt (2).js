// YouTube search — GET /api/yt?q=song+name&embed=1  →  { results:[{videoId,title,channel}] }
// Optional env var YT_API_KEY (YouTube Data API v3) makes it more reliable.
//     GET /api/yt?related=VIDEO_ID  →  songs like it (YouTube's "Up next")
const { searchYouTube, relatedYouTube } = require('./_yt');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  if (req.query && req.query.related) {
    const results = await relatedYouTube(req.query.related);
    if (results.length) res.setHeader('Cache-Control', 's-maxage=21600');
    return res.status(200).json({ results });
  }
  const q = String((req.query && req.query.q) || '').trim().slice(0, 200);
  if (!q) return res.status(400).json({ error: 'q is required', results: [] });
  const results = await searchYouTube(q, String((req.query && req.query.embed) || '') === '1');
  if (results.length) res.setHeader('Cache-Control', 's-maxage=3600');
  return res.status(200).json({ results });
};
