// Song search for the floating player — GET /api/music?q=song+name
// → { audius:[{title,artist,url}], youtube:[{videoId,title,channel}] }
// Audius songs play as pure background audio; YouTube songs play in the small visible video window.
const { searchYouTube, searchAudius } = require('./_yt');

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  const q = String((req.query && req.query.q) || '').trim().slice(0, 200);
  if (!q) return res.status(400).json({ error: 'q is required', audius: [], youtube: [] });
  const [youtube, audius] = await Promise.all([searchYouTube(q, true), searchAudius(q)]);
  return res.status(200).json({ audius, youtube });
};
