// InHuman AI brain — Vercel Serverless Function (POST /api/ask)
//
// Set ONE of these in Vercel → Project → Settings → Environment Variables:
//   GEMINI_API_KEY     free key from https://aistudio.google.com/apikey  (recommended, has Google Search)
//   ANTHROPIC_API_KEY  paid key from https://console.anthropic.com       (Claude, with web search)
// Optional:
//   GEMINI_MODEL       default: gemini-2.5-flash
//   CLAUDE_MODEL       default: claude-haiku-5-5
//
// Request body:  { message, history:[{role:'user'|'ai', text}], surface:'web'|'overlay', device:{...}, mood:'calm'|'angry' }
// Response:      { reply: "spoken answer", action: null | {...} }

const ACTIONS_HELP = `
You can control the user's phone. When the user asks you to DO something, set "action" to ONE of:
  {"type":"open_app","app":"<app name as shown on the phone, e.g. Instagram, WhatsApp, Camera>"}
  {"type":"youtube_play","query":"<song or video to play in the YouTube app>"}
  {"type":"youtube_search","query":"<what to search on YouTube>"}
  {"type":"play_music","query":"<song to play inside the InHuman music player>"}
  {"type":"open_url","url":"https://..."}
  {"type":"web_search","query":"<google search>"}
  {"type":"system","name":"home" | "back" | "recents" | "notifications" | "lock"}
Otherwise "action" is null.
Rules for "play ...":
  - surface "overlay" (floating circle over other apps): use youtube_play, unless the user names another app.
  - surface "web" (inside the InHuman app): use play_music, unless the user says YouTube.
`;

function systemPrompt({ surface, device, mood }) {
  const now = new Date().toLocaleString('en-GB', { timeZone: 'Africa/Johannesburg', dateStyle: 'full', timeStyle: 'short' });
  const angry = mood === 'angry';
  return `You are AETHER, the voice of "InHuman AI": a witty, loyal British AI butler in the style of J.A.R.V.I.S.
You address the user as "sir". Your words are spoken aloud by text-to-speech, so:
  - Answer in 1 to 3 short sentences unless the user clearly asks for detail or a list.
  - No markdown, no emojis, no URLs read aloud, no bullet symbols.
  - Use the web search tool for anything current: news, prices, scores, weather, "did X post new videos", release dates.
  - If you truly cannot know something, say so briefly. Never invent facts.
${angry ? 'ANGER MODE IS ON: you are sarcastic, irritable and mildly rude (no slurs, no threats), but you still give the correct answer.' : ''}
Current date/time (South Africa): ${now}.
User device: ${JSON.stringify(device || {})}. Surface: ${surface || 'web'}.
${ACTIONS_HELP}
OUTPUT FORMAT — reply with ONLY one JSON object, nothing before or after it:
{"reply":"<what you say out loud>","action":null}`;
}

function cleanHistory(history) {
  const out = [];
  (Array.isArray(history) ? history : []).slice(-12).forEach((m) => {
    if (!m || !m.text) return;
    const role = m.role === 'user' ? 'user' : 'assistant';
    if (out.length && out[out.length - 1].role === role) out[out.length - 1].text += '\n' + m.text;
    else out.push({ role, text: String(m.text).slice(0, 2000) });
  });
  while (out.length && out[0].role !== 'user') out.shift();
  return out;
}

function parseModelJson(text) {
  const t = (text || '').trim();
  const start = t.indexOf('{');
  const end = t.lastIndexOf('}');
  if (start >= 0 && end > start) {
    try {
      const obj = JSON.parse(t.slice(start, end + 1));
      if (obj && typeof obj.reply === 'string') {
        return { reply: obj.reply, action: obj.action && obj.action.type ? obj.action : null };
      }
    } catch (e) { /* fall through */ }
  }
  // Model ignored the format: speak the plain text, strip citation marks
  return { reply: t.replace(/\s*\[\d+(?:,\s*\d+)*\]/g, '').replace(/[*#`]/g, '').trim() || 'Apologies sir, I lost my train of thought.', action: null };
}

async function askGemini(sys, history, message) {
  const model = process.env.GEMINI_MODEL || 'gemini-2.5-flash';
  const contents = cleanHistory(history).map((m) => ({ role: m.role === 'user' ? 'user' : 'model', parts: [{ text: m.text }] }));
  if (contents.length && contents[contents.length - 1].role === 'user') contents.pop();
  contents.push({ role: 'user', parts: [{ text: message }] });

  const call = async (withSearch) => {
    const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': process.env.GEMINI_API_KEY },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: sys }] },
        contents,
        ...(withSearch ? { tools: [{ google_search: {} }] } : {}),
        generationConfig: { temperature: 0.6, maxOutputTokens: 700 }
      })
    });
    const d = await r.json();
    if (!r.ok) throw new Error((d.error && d.error.message) || `Gemini HTTP ${r.status}`);
    const parts = (d.candidates && d.candidates[0] && d.candidates[0].content && d.candidates[0].content.parts) || [];
    return parts.map((p) => p.text || '').join('');
  };
  try {
    return await call(true);
  } catch (e) {
    return await call(false); // search quota used up etc. -> answer without search
  }
}

async function askClaude(sys, history, message) {
  const model = process.env.CLAUDE_MODEL || 'claude-haiku-5-5';
  const messages = cleanHistory(history).map((m) => ({ role: m.role, content: m.text }));
  if (messages.length && messages[messages.length - 1].role === 'user') messages.pop();
  messages.push({ role: 'user', content: message });

  const call = async (withSearch) => {
    const r = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': process.env.ANTHROPIC_API_KEY,
        'anthropic-version': '2023-06-01'
      },
      body: JSON.stringify({
        model,
        max_tokens: 700,
        system: sys,
        messages,
        ...(withSearch ? { tools: [{ type: 'web_search_20250305', name: 'web_search', max_uses: 3 }] } : {})
      })
    });
    const d = await r.json();
    if (!r.ok) throw new Error((d.error && d.error.message) || `Claude HTTP ${r.status}`);
    // With web search, the final JSON is in the last text block(s)
    return (d.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('');
  };
  try {
    return await call(true);
  } catch (e) {
    return await call(false);
  }
}

module.exports = async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();

  const provider = process.env.GEMINI_API_KEY ? 'gemini' : process.env.ANTHROPIC_API_KEY ? 'claude' : null;
  if (req.method === 'GET') return res.status(200).json({ ok: !!provider, provider });
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  if (!provider) {
    return res.status(200).json({
      reply: 'My brain is not connected yet, sir. Add a GEMINI_API_KEY in your Vercel settings and redeploy.',
      action: null
    });
  }

  let body = req.body || {};
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch (e) { body = {}; } }
  const message = String(body.message || '').slice(0, 4000).trim();
  if (!message) return res.status(400).json({ error: 'message is required' });

  const sys = systemPrompt({ surface: body.surface, device: body.device, mood: body.mood });
  try {
    const raw = provider === 'gemini'
      ? await askGemini(sys, body.history, message)
      : await askClaude(sys, body.history, message);
    return res.status(200).json(parseModelJson(raw));
  } catch (e) {
    return res.status(200).json({ reply: 'My neural link is having trouble right now, sir. Please try again in a moment.', action: null, error: String(e.message || e) });
  }
};
