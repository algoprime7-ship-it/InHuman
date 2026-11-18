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
function template({ site, title, paragraphs, button, footnote }) {
  const logo = site ? `<img src="${site}/logo-email.png" width="120" height="120" alt="InHuman AI" style="display:block;margin:0 auto;width:120px;height:120px;border:0;">` : '<div style="font:700 22px Arial,sans-serif;color:#fff;text-align:center;">InHuman AI</div>';
  const body = paragraphs.map((p) => `<p style="margin:0 0 16px;font:15px/1.6 Arial,Helvetica,sans-serif;color:#d4d4d8;">${p}</p>`).join('');
  const btn = button && button.url
    ? `<table role="presentation" cellpadding="0" cellspacing="0" style="margin:8px 0 4px;"><tr><td style="border-radius:12px;background:#ffffff;"><a href="${esc(button.url)}" style="display:inline-block;padding:13px 26px;font:700 14px Arial,Helvetica,sans-serif;color:#000000;text-decoration:none;border-radius:12px;">${esc(button.text)}</a></td></tr></table>`
    : '';
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark"><meta name="supported-color-schemes" content="dark"><title>${esc(title)}</title></head>
<body style="margin:0;padding:0;background:#000000;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#000000;">
  <tr><td align="center" style="padding:36px 16px 12px;">${logo}</td></tr>
  <tr><td align="center" style="padding:12px 16px 32px;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:520px;border:1px solid #ffffff;border-radius:18px;background:#000000;">
      <tr><td style="padding:30px 28px;">
        <h1 style="margin:0 0 18px;font:700 22px/1.3 Arial,Helvetica,sans-serif;color:#ffffff;">${esc(title)}</h1>
        ${body}${btn}
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

module.exports = { mailReady, welcome, activated, template, siteUrl };
