// InHuman emails (welcome + subscription activated). Not a public route.
// Vercel → Settings → Environment Variables:
//   SMTP_USER   the email address that sends (e.g. hello@yourdomain.com or you@gmail.com)
//   SMTP_PASS   its password. Gmail: an APP PASSWORD (myaccount.google.com/apppasswords), not your normal one
//   SMTP_HOST   optional — worked out for Gmail, Outlook, Yahoo, iCloud, Zoho; set it for your own domain
//   SMTP_PORT   optional (465 default, 587 for Outlook)
//   SITE_URL    optional, e.g. https://inhuman.vercel.app (used for the logo and buttons)


const KNOWN = {
  'gmail.com': ['smtp.gmail.com', 465], 'googlemail.com': ['smtp.gmail.com', 465],
  'outlook.com': ['smtp-mail.outlook.com', 587], 'hotmail.com': ['smtp-mail.outlook.com', 587], 'live.com': ['smtp-mail.outlook.com', 587],
  'yahoo.com': ['smtp.mail.yahoo.com', 465], 'icloud.com': ['smtp.mail.me.com', 587], 'me.com': ['smtp.mail.me.com', 587],
  'zoho.com': ['smtp.zoho.com', 465]
};

function mailReady() {
  return !!(process.env.SMTP_USER && process.env.SMTP_PASS);
}

function siteUrl(req) {
  if (process.env.SITE_URL) return process.env.SITE_URL.replace(/\/+$/, '');
  const h = req && req.headers ? (req.headers['x-forwarded-host'] || req.headers.host) : '';
  return h ? `https://${String(h).split(',')[0].trim()}` : '';
}

const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/** Black page, logo on top, white outlined card with the message */
function template({ site, title, paragraphs, button, footnote, extra }) {
  const logo = site ? `<img src="${site}/logo-email.png" width="120" height="120" alt="InHuman AI" style="display:block;margin:0 auto;width:120px;height:120px;border:0;">` : '<div style="font:700 22px Arial,sans-serif;color:#fff;text-align:center;">InHuman AI</div>';
  const body = paragraphs.map((p) => `<p style="margin:0 0 16px;font:15px/1.6 Arial,Helvetica,sans-serif;color:#d4d4d8;">${p}</p>`).join('');
  const btn = button && button.url
    ? `<table role="presentation" cellpadding="0" cellspacing="0" ${button.center ? 'align="center" ' : ''}style="margin:8px ${button.center ? 'auto' : '0'} 4px;"><tr><td style="border-radius:12px;background:#ffffff;"><a href="${esc(button.url)}" style="display:inline-block;padding:13px 26px;font:700 14px Arial,Helvetica,sans-serif;color:#000000;text-decoration:none;border-radius:12px;">${esc(button.text)}</a></td></tr></table>`
    : '';
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><meta name="supported-color-schemes" content="dark"><title>${esc(title)}</title></head>
<body style="margin:0;padding:0;background:#000000;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#000000;">
  <tr><td align="center" style="padding:36px 16px 12px;">${logo}</td></tr>
  <tr><td align="center" style="padding:12px 16px 32px;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;border:1px solid #ffffff;border-radius:18px;background:#000000;">
      <tr><td style="padding:30px 28px;">
        <h1 style="margin:0 0 18px;font:700 22px/1.3 Arial,Helvetica,sans-serif;color:#ffffff;">${esc(title)}</h1>
        ${body}${extra || ''}${btn}
      </td></tr>
    </table>
    <p style="margin:22px 0 0;font:11px/1.5 Arial,Helvetica,sans-serif;color:#71717a;">${footnote || 'InHuman AI'}</p>
  </td></tr>
</table></body></html>`;
}

async function send(to, subject, html, text) {
  if (!mailReady()) return { sent: false, error: 'SMTP_USER / SMTP_PASS not set' };
  const user = process.env.SMTP_USER;
  const auto = KNOWN[(user.split('@')[1] || '').toLowerCase()];
  const host = process.env.SMTP_HOST || (auto && auto[0]);
  const port = Number(process.env.SMTP_PORT) || (auto && auto[1]) || 465;
  if (!host) return { sent: false, error: 'Set SMTP_HOST for this email domain' };
  const nodemailer = require('nodemailer');
  const transport = nodemailer.createTransport({
    host, port, secure: port === 465, auth: { user, pass: String(process.env.SMTP_PASS).replace(/\s+/g, '') },
    connectionTimeout: 8000, greetingTimeout: 8000, socketTimeout: 10000
  });
  try {
    await transport.sendMail({ from: `"${process.env.MAIL_FROM_NAME || 'InHuman AI'}" <${user}>`, to, subject, html, text });
    return { sent: true };
  } catch (e) {
    return { sent: false, error: String(e.message || e) };
  }
}

// never let an email hold up sign-up or approval for long
function capped(p, ms = 9000) {
  return Promise.race([p, new Promise((r) => setTimeout(() => r({ sent: false, error: 'timeout' }), ms))]).catch((e) => ({ sent: false, error: String(e) }));
}

function welcome(req, client) {
  const site = siteUrl(req);
  const name = esc(client.first || 'there');
  const html = template({
    site,
    title: `Welcome to InHuman, ${name}`,
    paragraphs: [
      'Your InHuman account is ready. Your history, configurations and playlists are now saved to it, so they follow you to any device.',
      'The free plan gives you 50 messages and 100 credits. When either runs out, everything refills 7 hours later.',
      'Want more? Upgrade any time in <b style="color:#ffffff;">Settings → Credits and billing</b> for more credits and every mode.'
    ],
    button: site ? { url: site, text: 'Open InHuman' } : null,
    footnote: `You're getting this because ${esc(client.email)} signed up for InHuman AI.`
  });
  const text = `Welcome to InHuman, ${client.first || ''}.\n\nYour account is ready. The free plan gives you 50 messages and 100 credits, refilled 7 hours after you run out.\nUpgrade any time in Settings → Credits and billing.\n\n${site}`;
  return capped(send(client.email, 'Welcome to InHuman', html, text));
}

function activated(req, client, tierName, credits) {
  const site = siteUrl(req);
  const html = template({
    site,
    title: `Your ${esc(tierName)} plan is active`,
    paragraphs: [
      `Hi ${esc(client.first || 'there')}, your payment was approved and your <b style="color:#ffffff;">${esc(tierName)}</b> subscription is now active.`,
      `<b style="color:#ffffff;">${esc(credits)}</b> credits were added. Your balance is now <b style="color:#ffffff;">${esc(client.balance)}</b> credits.`,
      'No more daily limits, and every mode is unlocked. Thank you for supporting InHuman.'
    ],
    button: site ? { url: site, text: 'Start using InHuman' } : null,
    footnote: `Sent to ${esc(client.email)} for your InHuman AI subscription.`
  });
  const text = `Your ${tierName} plan is active.\n${credits} credits were added. Balance: ${client.balance} credits.\n\n${site}`;
  return capped(send(client.email, `Your ${tierName} subscription is active`, html, text));
}

// ---------------- MT5 ----------------
const money = (v, cur) => (v == null || v === '' || Number.isNaN(Number(v)) ? '—' : `${Number(v).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${esc(cur || '')}`.trim());

/** White-outlined details table for the email card */
function rows(list) {
  const tr = list.filter((r) => r && r[1] !== undefined && r[1] !== null && r[1] !== '').map(([k, v]) =>
    `<tr><td style="padding:9px 12px;border-bottom:1px solid #27272a;font:12px Arial,Helvetica,sans-serif;color:#a1a1aa;white-space:nowrap;">${esc(k)}</td><td style="padding:9px 12px;border-bottom:1px solid #27272a;font:700 13px Arial,Helvetica,sans-serif;color:#ffffff;text-align:right;">${v}</td></tr>`).join('');
  return `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:4px 0 20px;border:1px solid #3f3f46;border-radius:12px;border-collapse:separate;overflow:hidden;">${tr}</table>`;
}
const closing = '<p style="margin:0 0 6px;font:700 15px Arial,Helvetica,sans-serif;color:#ffffff;">InHuman AI is connected.</p>';

function mt5Connected(req, client, a, s) {
  const site = siteUrl(req);
  const html = template({
    site,
    title: 'Your MT5 trading account is connected',
    paragraphs: [
      `Hi ${esc(client.first || 'there')}, your MetaTrader account is connected and ready to take trades. All systems are online.`,
      'Here is your connected account:'
    ],
    extra: rows([
      ['Account name', esc(a.name)], ['Login', esc(s.login)], ['Broker', esc(s.broker || a.company)], ['Server', esc(s.server)],
      ['Balance', money(a.balance, a.currency)], ['Equity', money(a.equity, a.currency)], ['Free margin', money(a.freeMargin, a.currency)],
      ['Leverage', a.leverage ? `1:${esc(a.leverage)}` : ''], ['Target', `${money(s.target, a.currency)} (×${s.multiplier})`]
    ]) + closing,
    button: site ? { url: site, text: 'Open InHuman' } : null,
    footnote: `Sent to ${esc(client.email)} because an MT5 account was connected to InHuman AI.`
  });
  const text = `Hi ${client.first || ''}, your MetaTrader account ${s.login} (${s.server}) is connected and ready to take trades. All systems are online.\nBalance: ${a.balance} ${a.currency}\nTarget: ${s.target}\n\nInHuman AI is connected.`;
  return capped(send(client.email, 'Your MT5 trading account is connected', html, text));
}

function tradeExecuted(req, client, t) {
  const site = siteUrl(req);
  const when = new Date(t.at).toLocaleString('en-ZA', { timeZone: 'Africa/Johannesburg', dateStyle: 'medium', timeStyle: 'short' });
  const html = template({
    site,
    title: `Trade executed on ${esc(t.symbol)}`,
    paragraphs: [`Hi ${esc(client.first || 'there')}, ${t.auto ? 'the auto trader' : 'InHuman'} just opened ${t.opened} ${t.direction === 'BUY' ? 'buy' : 'sell'} trade${t.opened > 1 ? 's' : ''} on <b style="color:#ffffff;">${esc(t.symbol)}</b>.`],
    extra: rows([
      ['Symbol', esc(t.symbol)],
      ['Direction', `<span style="color:${t.direction === 'BUY' ? '#34d399' : '#f87171'};">${esc(t.direction)}</span>`],
      ['Lot size', esc(t.lot)], ['Number of trades', `${t.opened}${t.opened !== t.count ? ` of ${t.count}` : ''}`],
      ['Confidence', t.confidence != null ? `${esc(t.confidence)}%` : ''], ['Timeframes used', esc((t.timeframes || []).join(', '))],
      ['Take profit', t.tp ? esc(t.tp) : 'none'], ['Stop loss', t.sl ? esc(t.sl) : 'none'],
      ['Comment', 'InHuman AI'], ['Time', esc(when)],
      ['Tickets', esc((t.tickets || []).join(', '))]
    ]) + closing,
    button: site ? { url: site, text: 'View in InHuman' } : null,
    footnote: `Sent to ${esc(client.email)} for your connected MT5 account.`
  });
  const text = `Trade executed on ${t.symbol}\n${t.direction} ${t.opened} x ${t.lot} lots\nConfidence: ${t.confidence ?? '-'}%\nTimeframes: ${(t.timeframes || []).join(', ')}\nTP: ${t.tp || 'none'}  SL: ${t.sl || 'none'}\nTickets: ${(t.tickets || []).join(', ')}\n\nInHuman AI is connected.`;
  return capped(send(client.email, `Trade executed on ${t.symbol}`, html, text));
}

function targetReached(req, client, { took, losses, account, s }) {
  const site = siteUrl(req);
  const lossLine = losses
    ? (losses.count ? `We ran a few losses: <b style="color:#ffffff;">${losses.count}</b> trade${losses.count > 1 ? 's' : ''} closed by stop loss, and <b style="color:#ffffff;">${money(losses.lost, account.currency)}</b> was lost along the way.` : 'And we did it with no losing trades.')
    : 'We ran a few losses along the way.';
  const html = template({
    site,
    title: 'Target reached',
    paragraphs: [
      `Hello sir, I'm happy to say that today we have reached the target.`,
      `It took <b style="color:#ffffff;">${esc(took)}</b> to reach. It was kinda hard, but we completed it. ${lossLine}`
    ],
    extra: rows([
      ['Started with', money(s.startBalance, account.currency)], ['Target', `${money(s.target, account.currency)} (×${s.multiplier})`],
      ['Balance now', money(account.balance, account.currency)], ['Equity now', money(account.equity, account.currency)],
      ['Time taken', esc(took)], ['Losing trades', losses ? String(losses.count) : ''], ['Amount lost', losses ? money(losses.lost, account.currency) : '']
    ]) + '<p style="margin:0 0 16px;font:15px/1.6 Arial,Helvetica,sans-serif;color:#d4d4d8;">Auto trading is now stopped. Set a new target in the app to keep going.</p>' + closing,
    button: site ? { url: site, text: 'Open InHuman' } : null,
    footnote: `Sent to ${esc(client.email)} for your connected MT5 account.`
  });
  const text = `Target reached. It took ${took}. Started ${s.startBalance}, now ${account.equity}. ${losses ? `${losses.count} losing trades, ${losses.lost} lost.` : ''}`;
  return capped(send(client.email, 'Target reached', html, text));
}

function accountLost(req, client, { account, s, losses }) {
  const site = siteUrl(req);
  const html = template({
    site,
    title: 'Your trading account has been lost',
    paragraphs: [
      `Hello sir, I'm sorry. Your MT5 account ${esc(s.login)} has run down to <b style="color:#ffffff;">${money(account.equity ?? account.balance, account.currency)}</b>.`,
      'I have stopped all auto trading on this account. Nothing more will be opened until you reconnect.'
    ],
    extra: rows([
      ['Started with', money(s.startBalance, account.currency)], ['Balance now', money(account.balance, account.currency)],
      ['Equity now', money(account.equity, account.currency)], ['Losing trades', losses ? String(losses.count) : ''], ['Amount lost', losses ? money(losses.lost, account.currency) : '']
    ]),
    button: site ? { url: site, text: 'Open InHuman' } : null,
    footnote: `Sent to ${esc(client.email)} for your connected MT5 account.`
  });
  const text = `Your MT5 account ${s.login} has run down to ${account.equity}. Auto trading is stopped.`;
  return capped(send(client.email, 'Your trading account has been lost', html, text));
}

// ---------------- invite another person (written like JARVIS) ----------------
function invite(req, from, email, code) {
  const site = siteUrl(req);
  const who = esc([from.first, from.last].filter(Boolean).join(' ') || from.email);
  const link = `${site}/?invite=${code}`;
  const html = template({
    site,
    title: `${who} would like you to join InHuman AI`,
    paragraphs: [
      'Good day. Allow me to introduce myself: I am the assistant inside InHuman AI.',
      `${who} has asked me to extend an invitation to connect with them. Once you approve, you'll be able to message each other, share where you are if you both choose to, and even play music on each other's devices. Rather convenient, if I may say so.`,
      'Simply press the button below. No password required; this invitation already knows who you are.'
    ],
    button: { url: link, text: 'Approve invite', center: true },
    footnote: `Sent to ${esc(email)} because ${who} invited you to InHuman AI. If you weren't expecting this, you may safely ignore it; the invitation expires in 14 days.`
  });
  const text = `${who} invited you to connect on InHuman AI. Approve the invite: ${link}`;
  return capped(send(email, `${who} invited you to InHuman AI`, html, text));
}

module.exports = { mailReady, welcome, activated, template, siteUrl, mt5Connected, tradeExecuted, targetReached, accountLost, invite };
