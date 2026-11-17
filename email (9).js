// Send email from the user's own account — POST /api/email
// Body: { smtp:{ host, port, user, pass }, fromName, to:["a@b.com", ...], subject, text }
// Reply: { sent: n, failed: [{to, error}] }
//
// The password is the account's APP PASSWORD (Gmail: myaccount.google.com/apppasswords).
// It's used for this one send and never stored on the server.
// Each recipient gets their own copy (they don't see each other's addresses).
const nodemailer = require('nodemailer');
const { cors, readBody } = require('./_ai');

const EMAIL_RE = /^[^\s@<>"']+@[^\s@<>"']+\.[^\s@<>"']+$/;

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const b = readBody(req);
  const s = b.smtp || {};
  const to = [...new Set((Array.isArray(b.to) ? b.to : []).map((x) => String(x).trim().toLowerCase()))].filter((x) => EMAIL_RE.test(x));
  if (!s.host || !s.user || !s.pass) return res.status(400).json({ error: 'Email account is missing host, address or app password' });
  if (!to.length) return res.status(400).json({ error: 'No valid recipient emails' });
  if (to.length > 100) return res.status(400).json({ error: 'Max 100 recipients per send' });

  const port = Number(s.port) || 465;
  const transport = nodemailer.createTransport({
    host: s.host,
    port,
    secure: port === 465,
    auth: { user: s.user, pass: s.pass }
  });

  try {
    await transport.verify();
  } catch (e) {
    return res.status(200).json({ sent: 0, failed: to.map((t) => ({ to: t, error: 'login failed' })), error: 'Could not log in to the email account. Check the app password.' });
  }

  const subject = String(b.subject || '').slice(0, 300) || '(no subject)';
  const text = String(b.text || '').slice(0, 50000);
  const from = b.fromName ? `"${String(b.fromName).replace(/"/g, '')}" <${s.user}>` : s.user;
  let sent = 0;
  const failed = [];
  for (const addr of to) {
    try {
      await transport.sendMail({ from, to: addr, subject, text });
      sent++;
    } catch (e) {
      failed.push({ to: addr, error: String(e.message || e).slice(0, 200) });
    }
  }
  return res.status(200).json({ sent, failed });
};
