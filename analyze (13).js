// Market direction check before opening trades — POST /api/analyze
// Body: { symbol:"XAUUSD", keys:{...} }   Reply: { direction:"BUY"|"SELL", reason:"one sentence" }
//
// This is an AI read of current news and price action (with web search). It is NOT a proven
// trading strategy and can be wrong.
const { callAI, parseJsonReply, cors, readBody } = require('./_ai');

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const b = readBody(req);
  const symbol = String(b.symbol || '').toUpperCase().replace(/[^A-Z0-9.]/g, '').slice(0, 20);
  if (!symbol) return res.status(400).json({ error: 'symbol is required' });

  const now = new Date().toISOString();
  const system = `You are a short-term market analyst. Time now (UTC): ${now}.
Use web search for the latest price action, news and sentiment for the instrument.
Decide the most likely direction for the next few hours: BUY or SELL. You must pick one.
Reply with ONLY this JSON: {"direction":"BUY"|"SELL","reason":"one short spoken sentence explaining why"}`;

  try {
    const raw = await callAI({ system, history: [], message: `Instrument: ${symbol}. Which direction now?`, keys: b.keys, maxTokens: 300, fast: false });
    const obj = parseJsonReply(raw) || {};
    let direction = String(obj.direction || '').toUpperCase();
    if (direction !== 'BUY' && direction !== 'SELL') {
      direction = /\bsell|bear/i.test(raw) ? 'SELL' : 'BUY';
    }
    const reason = String(obj.reason || '').replace(/\s*\[\d+(?:,\s*\d+)*\]/g, '').slice(0, 300) || 'Based on current momentum.';
    return res.status(200).json({ direction, reason });
  } catch (e) {
    return res.status(200).json({ error: e.code === 'NO_KEY' ? 'No AI key configured' : String(e.message || e) });
  }
};
