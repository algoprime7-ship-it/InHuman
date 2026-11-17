// Shared AI caller for /api/ask and /api/analyze (files starting with "_" are not public routes on Vercel).
//
// Server keys (Vercel → Settings → Environment Variables), first one found is used:
//   GEMINI_API_KEY (free, has Google Search) · ANTHROPIC_API_KEY (Claude) · OPENAI_API_KEY (ChatGPT)
// Users can also add their OWN keys in the phone app (Settings → Connect Apps); those win.

function cleanHistory(history) {
  const out = [];
  (Array.isArray(history) ? history : []).slice(-12).forEach((m) => {
    if (!m || !m.text) return;
    const role = m.role === 'user' ? 'user' : 'assistant';
    if (out.length && out[out.length - 1].role === role) out[out.length - 1].text += '\n' + m.text;
    else out.push({ role, text: String(m.text).slice(0, 2000) });
  });
  while (out.length && out[0].role !== 'user') out.shift();
  if (out.length && out[out.length - 1].role === 'user') out.pop();
  return out;
}

function pickProvider(keys = {}) {
  const user = {
    gemini: keys.gemini && String(keys.gemini).trim(),
    claude: keys.claude && String(keys.claude).trim(),
    openai: keys.openai && String(keys.openai).trim()
  };
  const pref = String(keys.preferred || '').toLowerCase();
  if (pref && user[pref]) return { name: pref, key: user[pref] };
  for (const n of ['gemini', 'claude', 'openai']) if (user[n]) return { name: n, key: user[n] };
  if (process.env.GEMINI_API_KEY) return { name: 'gemini', key: process.env.GEMINI_API_KEY };
  if (process.env.ANTHROPIC_API_KEY) return { name: 'claude', key: process.env.ANTHROPIC_API_KEY };
  if (process.env.OPENAI_API_KEY) return { name: 'openai', key: process.env.OPENAI_API_KEY };
  return null;
}

async function gemini(key, system, history, message, search, maxTokens) {
  const model = process.env.GEMINI_MODEL || 'gemini-2.5-flash';
  const contents = history.map((m) => ({ role: m.role === 'user' ? 'user' : 'model', parts: [{ text: m.text }] }));
  contents.push({ role: 'user', parts: [{ text: message }] });
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: system }] },
      contents,
      ...(search ? { tools: [{ google_search: {} }] } : {}),
      generationConfig: { temperature: 0.6, maxOutputTokens: maxTokens }
    })
  });
  const d = await r.json();
  if (!r.ok) throw new Error((d.error && d.error.message) || `Gemini HTTP ${r.status}`);
  const parts = (d.candidates && d.candidates[0] && d.candidates[0].content && d.candidates[0].content.parts) || [];
  return parts.map((p) => p.text || '').join('');
}

async function claude(key, system, history, message, search, maxTokens) {
  const model = process.env.CLAUDE_MODEL || 'claude-haiku-5-5';
  const messages = history.map((m) => ({ role: m.role, content: m.text }));
  messages.push({ role: 'user', content: message });
  const r = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-api-key': key, 'anthropic-version': '2023-06-01' },
    body: JSON.stringify({
      model,
      max_tokens: maxTokens,
      system,
      messages,
      ...(search ? { tools: [{ type: 'web_search_20250305', name: 'web_search', max_uses: 3 }] } : {})
    })
  });
  const d = await r.json();
  if (!r.ok) throw new Error((d.error && d.error.message) || `Claude HTTP ${r.status}`);
  return (d.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('');
}

async function openai(key, system, history, message, _search, maxTokens) {
  const model = process.env.OPENAI_MODEL || 'gpt-4o-mini';
  const messages = [{ role: 'system', content: system }]
    .concat(history.map((m) => ({ role: m.role, content: m.text })))
    .concat([{ role: 'user', content: message }]);
  const r = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${key}` },
    body: JSON.stringify({ model, messages, max_tokens: maxTokens, temperature: 0.6 })
  });
  const d = await r.json();
  if (!r.ok) throw new Error((d.error && d.error.message) || `OpenAI HTTP ${r.status}`);
  return (d.choices && d.choices[0] && d.choices[0].message && d.choices[0].message.content) || '';
}

const CALLERS = { gemini, claude, openai };

/** Returns the model's text, or throws. Tries with web search first, then without. */
async function callAI({ system, history, message, keys, search = true, maxTokens = 800 }) {
  const p = pickProvider(keys);
  if (!p) {
    const e = new Error('NO_KEY');
    e.code = 'NO_KEY';
    throw e;
  }
  const h = cleanHistory(history);
  try {
    return await CALLERS[p.name](p.key, system, h, message, search, maxTokens);
  } catch (e) {
    if (!search) throw e;
    return await CALLERS[p.name](p.key, system, h, message, false, maxTokens);
  }
}

function parseJsonReply(text) {
  const t = (text || '').trim();
  const start = t.indexOf('{');
  const end = t.lastIndexOf('}');
  if (start >= 0 && end > start) {
    try { return JSON.parse(t.slice(start, end + 1)); } catch (e) { /* fall through */ }
  }
  return null;
}

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function readBody(req) {
  let body = req.body || {};
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch (e) { body = {}; } }
  return body;
}

module.exports = { callAI, parseJsonReply, pickProvider, cors, readBody };
