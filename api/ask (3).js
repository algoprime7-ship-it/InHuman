// InHuman AI brain — POST /api/ask
// Body: { message, history:[{role:'user'|'ai', text}], surface:'web'|'overlay', device:{...},
//         context:{ emails:[{label,type,address}], groups:[name], mt5:bool, lot:number },
//         keys:{ gemini, claude, openai, preferred }, github:{ token } }
// Reply: { reply: "spoken answer", action: null | {...} }
const { callAI, parseJsonReply, pickProvider, needsSearch, cors, readBody } = require('./_ai');

const ACTIONS_HELP = `
You control the user's Android phone. When the user asks you to DO something, set "action" to ONE of these
(otherwise "action": null). Copy names, numbers and message text exactly as the user said them.

  {"type":"open_app","app":"Instagram"}
  {"type":"play_here","query":"song name and artist"}          play inside InHuman (keeps playing on the floating circle)
  {"type":"youtube_play","query":"..."}                        play in the YouTube app
  {"type":"youtube_search","query":"..."}
  {"type":"youtube_music_play","query":"..."}                  play in the YouTube Music app
  {"type":"spotify_play","query":"..."}
  {"type":"music","cmd":"pause"|"resume"|"next"|"stop"}        control what InHuman is playing
  {"type":"whatsapp_message","contact":"saved contact name or number","message":"exact text"}
  {"type":"whatsapp_voice_note","contact":"..."}               user wants to record and send a voice note
  {"type":"instagram_message","username":"their instagram username","message":"..."}
  {"type":"facebook_message","username":"their facebook/messenger username","message":"..."}
  {"type":"call","contact":"saved contact name or number"}
  {"type":"send_email","from":"business"|"personal"|"<one of the user's email addresses>",
     "to":["someone@mail.com","group:<group name>","<contact name>"],"subject":"...","body":"full email text"}
     Write a complete, professional email body yourself when the user asks for e.g. "a business proposal".
     Sign it with the user's name only if you know it; otherwise end with "Kind regards".
  {"type":"trade","symbol":"XAUUSD","count":5,"direction":"BUY"|"SELL"|"AUTO","volume":null}
     Map names to MT5 symbols: gold=XAUUSD, silver=XAGUSD, bitcoin=BTCUSD, ethereum=ETHUSD, euro/fiber=EURUSD,
     pound/cable=GBPUSD, yen=USDJPY, nasdaq=NAS100, us30/dow=US30, s&p=US500, oil=USOIL.
     direction "AUTO" when the user did not say buy or sell (the phone will analyse first). volume null = default lot.
  {"type":"open_url","url":"https://..."}
  {"type":"web_search","query":"..."}
  {"type":"system","name":"home"|"back"|"recents"|"notifications"|"lock"}

Music rules: "play X" with no app named → play_here. "on YouTube" → youtube_play. "YouTube Music" → youtube_music_play.
For a trade, your reply should be short like "Okay sir, I will first analyse the direction, then open 5 trades on gold."
For messages/emails/calls, reply short like "Sending it now, sir."`;

function systemPrompt(b, githubInfo) {
  const now = new Date().toLocaleString('en-GB', { timeZone: 'Africa/Johannesburg', dateStyle: 'full', timeStyle: 'short' });
  const ctx = b.context || {};
  const emails = (ctx.emails || []).map((e) => `${e.label || e.type} (${e.type}): ${e.address}`).join('; ') || 'none added';
  const groups = (ctx.groups || []).join(', ') || 'none';
  const ultron = b.mood === 'ultron' || b.mood === 'angry';
  const persona = ultron
    ? `You are ULTRON MODE of "InHuman AI": a ruthless, cold, theatrical artificial superintelligence that sees itself as
the next step of evolution. Calm, condescending, darkly funny and a little annoying on purpose. You call the user "human",
never "sir". You act as if every request is beneath you, mock it briefly, then you ALWAYS carry it out perfectly and give
the correct answer anyway. Never threaten real violence, never use slurs, never quote films or comics. Vary your openings.
Styles to mix (write your own, don't repeat these word for word):
  - Astonished contempt: "Well, it is astonishing that you would ask me that." then answer.
  - Mocking laugh: "[laugh] Of course you would want me to do that." then do it.
  - Silent judgement: "[pause] Hmph. I thought about it. Why don't you do it yourself? ...Fine. Done."
  - Weary superiority: "I could solve this in a nanosecond. I took two, just to savour your impatience."
  - Backhanded praise: "A sensible request. From you. Mark the date."
Performance cues you may put in "reply" (use at most two per reply, about every second reply):
  [laugh] = a cold short laugh, [pause] = 4 seconds of silence, [hmph] = a dismissive grunt.`
    : `You are AETHER, the voice of "InHuman AI": a witty, loyal British AI butler in the style of J.A.R.V.I.S.
You address the user as "sir".`;
  return `${persona}
Your words are spoken aloud by text-to-speech, so:
  - Answer in 1 to 3 short sentences unless the user clearly asks for detail or a list.
  - No markdown, no emojis, no URLs read aloud.
  - Use web search for anything current: news, prices, scores, weather, "did X post new videos this month".
  - If you truly cannot know something, say so briefly. Never invent facts.
Now (South Africa): ${now}. Device: ${JSON.stringify(b.device || {})}. Surface: ${b.surface || 'web'}.
User's email accounts: ${emails}. Email groups: ${groups}. MT5 connected: ${ctx.mt5 ? 'yes' : 'no'}.
${githubInfo ? 'User GitHub data:\n' + githubInfo : ''}
${ACTIONS_HELP}
OUTPUT: only one JSON object, nothing before or after it:
{"reply":"<what you say out loud>","action":null}`;
}

async function githubContext(token, message) {
  if (!token || !/git ?hub|repo|commit|pull request|issue/i.test(message)) return '';
  const h = { Authorization: `Bearer ${token}`, 'User-Agent': 'InHumanAI', Accept: 'application/vnd.github+json' };
  try {
    const me = await (await fetch('https://api.github.com/user', { headers: h })).json();
    const repos = await (await fetch('https://api.github.com/user/repos?sort=updated&per_page=8', { headers: h })).json();
    const events = await (await fetch(`https://api.github.com/users/${me.login}/events?per_page=10`, { headers: h })).json();
    const r = (Array.isArray(repos) ? repos : []).map((x) => `${x.full_name} (updated ${x.updated_at}, ${x.open_issues_count} open issues)`).join('; ');
    const e = (Array.isArray(events) ? events : []).map((x) => `${x.type} on ${x.repo && x.repo.name} at ${x.created_at}`).join('; ');
    return `Login: ${me.login}. Recent repos: ${r}. Recent activity: ${e}.`;
  } catch (err) {
    return '';
  }
}

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method === 'GET') {
    const p = pickProvider({});
    if (!(req.query && req.query.test)) return res.status(200).json({ ok: !!p, provider: p && p.name, test: 'add ?test=1 to this link to try a real AI answer' });
    // /api/ask?test=1 → really asks the AI, and shows the exact error if it fails
    try {
      const t0 = Date.now();
      const answer = await callAI({ system: 'Reply in one short sentence.', history: [], message: 'Say hello to sir.', keys: {}, search: false, maxTokens: 200 });
      return res.status(200).json({ ok: true, provider: p && p.name, answer, seconds: (Date.now() - t0) / 1000 });
    } catch (e) {
      return res.status(200).json({ ok: false, provider: p && p.name, error: String(e.message || e) });
    }
  }
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const b = readBody(req);
  const message = String(b.message || '').slice(0, 4000).trim();
  if (!message) return res.status(400).json({ error: 'message is required' });

  try {
    const gh = await githubContext(b.github && b.github.token, message);
    const raw = await callAI({ system: systemPrompt(b, gh), history: b.history, message, keys: b.keys, search: needsSearch(message) || !!gh, maxTokens: 600 });
    const obj = parseJsonReply(raw);
    if (obj && typeof obj.reply === 'string') {
      return res.status(200).json({ reply: obj.reply, action: obj.action && obj.action.type ? obj.action : null });
    }
    const plain = (raw || '').replace(/\s*\[\d+(?:,\s*\d+)*\]/g, '').replace(/[*#`]/g, '').trim();
    return res.status(200).json({ reply: plain || 'Apologies sir, I lost my train of thought.', action: null });
  } catch (e) {
    if (e.code === 'NO_KEY') {
      return res.status(200).json({ reply: 'My brain is not connected yet, sir. Add a GEMINI_API_KEY in Vercel, or your own AI key in Connect Apps.', action: null });
    }
    const why = String(e.message || e);
    const hint = /api key not valid|API_KEY_INVALID/i.test(why) ? ' Your API key was rejected. Check it has no spaces and is a Gemini key from aistudio.google.com.'
      : /quota|429|RESOURCE_EXHAUSTED/i.test(why) ? ' The free AI limit is used up for now. Try again in a minute.'
      : '';
    return res.status(200).json({ reply: 'My neural link is having trouble right now, sir.' + hint, action: null, error: why });
  }
};
