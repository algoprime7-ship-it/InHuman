// Shared AI caller for /api/ask and /api/analyze (files starting with "_" are not public routes on Vercel).
//
// Server keys (Vercel → Settings → Environment Variables):
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

// Gemini model names change often (old ones get shut down), so try several, newest aliases first.
// fast = Flash-Lite first (no "thinking" → quickest replies). Otherwise the full Flash model first.
const GEMINI_MODELS = (fast) => [process.env.GEMINI_MODEL]
  .concat(fast
    ? ['gemini-flash-lite-latest', 'gemini-flash-latest', 'gemini-3.8-flash', 'gemini-2.5-flash']
    : ['gemini-flash-latest', 'gemini-3.8-flash', 'gemini-flash-lite-latest', 'gemini-2.5-flash'])
  .filter((m, i, a) => m && a.indexOf(m) === i);

// Keep thinking as short as each model allows (thinking is the main cause of slow answers)
function thinkingFor(model) {
  if (/lite/.test(model)) return null;                       // Flash-Lite doesn't think by default
  if (/2\.5/.test(model)) return { thinkingBudget: 0 };     // Gemini 2.5: switch thinking off
  return { thinkingLevel: 'low' };                           // Gemini 3.x: lowest level every Flash model accepts
}

async function geminiOnce(model, key, system, contents, search, maxTokens) {
  const thinking = thinkingFor(model);
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: system }] },
      contents,
      ...(search ? { tools: [{ google_search: {} }] } : {}),
      generationConfig: { maxOutputTokens: maxTokens, ...(thinking ? { thinkingConfig: thinking } : {}) }
    })
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) {
    const e = new Error(`Gemini ${model}: ${(d.error && d.error.message) || 'HTTP ' + r.status}`);
    e.status = r.status;
    throw e;
  }
  const parts = (d.candidates && d.candidates[0] && d.candidates[0].content && d.candidates[0].content.parts) || [];
  const text = parts.filter((p) => !p.thought).map((p) => p.text || '').join('');
  if (!text.trim()) throw new Error(`Gemini ${model}: empty answer`);
  return text;
}

async function gemini(key, system, history, message, search, maxTokens, fast = true) {
  const contents = history.map((m) => ({ role: m.role === 'user' ? 'user' : 'model', parts: [{ text: m.text }] }));
  contents.push({ role: 'user', parts: [{ text: message }] });
  let lastErr;
  for (const model of GEMINI_MODELS(fast)) {
    try {
      return await geminiOnce(model, key, system, contents, search, Math.max(maxTokens, 1024));
    } catch (e) {
      lastErr = e;
      // bad key / no permission: trying other models won't help
      if (e.status === 400 && /api key/i.test(e.message)) throw e;
      if (e.status === 401 || e.status === 403) throw e;
    }
  }
  throw lastErr;
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
    body: JSON.stringify({ model, messages, max_tokens: maxTokens })
  });
  const d = await r.json();
  if (!r.ok) throw new Error((d.error && d.error.message) || `OpenAI HTTP ${r.status}`);
  return (d.choices && d.choices[0] && d.choices[0].message && d.choices[0].message.content) || '';
}

const CALLERS = { gemini, claude, openai };

function providerList(keys = {}, adminKeys = {}) {
  const list = [];
  const add = (name, key) => { if (key && !list.some((p) => p.key === key)) list.push({ name, key: String(key).trim() }); };
  const pref = String(keys.preferred || '').toLowerCase();
  if (pref && keys[pref]) add(pref, keys[pref]);
  ['gemini', 'claude', 'openai'].forEach((n) => add(n, keys[n]));          // user's own keys (phone app settings)
  add('gemini', process.env.GEMINI_API_KEY);                                // then the server's keys (Vercel)
  add('claude', process.env.ANTHROPIC_API_KEY);
  add('openai', process.env.OPENAI_API_KEY);
  ['gemini', 'claude', 'openai'].forEach((n) => add(n, adminKeys && adminKeys[n]));   // then keys saved in /admin → Configurations
  return list;
}

/** Returns the model's text, or throws. Tries each available key; each with web search first, then without. */
async function callAI({ system, history, message, keys, adminKeys, search = true, maxTokens = 800, fast = true }) {
  const providers = providerList(keys || {}, adminKeys || {});
  if (!providers.length) {
    const e = new Error('NO_KEY');
    e.code = 'NO_KEY';
    throw e;
  }
  const h = cleanHistory(history);
  const errors = [];
  for (const p of providers) {
    try {
      return await CALLERS[p.name](p.key, system, h, message, search, maxTokens, fast);
    } catch (e) {
      errors.push(e.message);
      if (search) {
        try {
          return await CALLERS[p.name](p.key, system, h, message, false, maxTokens, fast);
        } catch (e2) {
          errors.push(e2.message);
        }
      }
    }
  }
  throw new Error(errors.join(' | ').slice(0, 600));
}

/** Web search is slow: only use it when the message is about current / real-world info */
function needsSearch(message) {
  return /\b(news|today|tonight|yesterday|tomorrow|this (week|month|year)|last (week|month|night)|latest|current|currently|right now|now|recent|recently|new (video|videos|song|album|episode|release)|drop(ped)?|upload(ed)?|released?|price|cost|how much|weather|forecast|temperature|score|won|winner|match|game|fixture|standings|stock|share price|crypto|bitcoin|btc|gold|xauusd|forex|exchange rate|rand|dollar|who is|who's|is .* (still|alive|dead)|election|president|ceo|trending|update|202[4-9]|live)\b/i.test(message || '');
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

module.exports = { callAI, parseJsonReply, pickProvider, providerList, needsSearch, cors, readBody };
