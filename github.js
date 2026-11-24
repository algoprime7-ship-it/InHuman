// Work inside the user's GitHub — POST /api/github
// Body: { op, github:{ token }, repo?, path?, ... , keys?, token?, deviceId? }
// Reply: { ok, say, ... }   "say" is the short sentence Jarvis speaks.
//
// The token is the USER'S OWN GitHub token (Settings → Connect Apps → GitHub token). It is used for this one
// request and is never stored or logged. The admin's GitHub key is never used here.
// Needs a token with the "repo" scope (classic) or Contents / Pull requests / Issues: read+write (fine-grained).
//
//   repos                                    list the user's recent repos
//   list    { repo, path?, ref? }            files in a folder
//   read    { repo, path, ref? }             read a file
//   issues  { repo }  ·  prs { repo }        open issues / pull requests
//   create_repo  { name, description?, private? }               (private unless told otherwise)
//   create_issue { repo, title, body? }
//   write_file   { repo, path, content, message?, branch?, direct?, pr? }
//   code_file    { repo, path, task, message?, branch?, direct?, pr? }   the AI writes the code, then it is committed
//   create_pr    { repo, head, title?, body?, base? }
//
// Safety rules enforced here (not just in the prompt):
//   • Code goes to a NEW branch "inhuman/…" unless direct:true AND the branch is the default branch.
//   • No deleting, no force-push, no merging, no repo deletion. Those are not offered.
//   • Nothing under .github/workflows/ can be written (workflows can run code with your secrets).
const { callAI, cors, readBody } = require('./_ai');
const B = require('./_billing');

const GH = 'https://api.github.com';
const MAX_FILE = 200 * 1024;

const ghHeaders = (token) => ({
  Authorization: `Bearer ${token}`, 'User-Agent': 'InHumanAI', Accept: 'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json'
});

async function gh(token, method, path, body) {
  const r = await fetch(GH + path, { method, headers: ghHeaders(token), body: body ? JSON.stringify(body) : undefined });
  let data = null;
  try { data = await r.json(); } catch (e) { /* empty body */ }
  if (!r.ok) {
    const msg = (data && data.message) || `GitHub said ${r.status}`;
    const err = new Error(r.status === 401 ? 'GitHub rejected the token' : r.status === 403 || r.status === 404
      ? `${msg} (check the token has the "repo" scope and can see that repo)` : msg);
    err.status = r.status;
    throw err;
  }
  return data;
}

const slug = (s) => String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30) || 'change';
const stripFence = (t) => {
  const s = String(t || '').trim();
  const m = s.match(/^```[\w+-]*\n([\s\S]*?)\n```\s*$/);
  return m ? m[1] : s;
};

function cleanPath(p) {
  const x = String(p || '').replace(/\\/g, '/').replace(/^\/+/, '').trim();
  if (!x || x.length > 200 || x.split('/').some((seg) => seg === '..' || seg === '.git')) return '';
  return x;
}

async function fullRepo(token, repo) {
  repo = String(repo || '').trim().replace(/^https?:\/\/github\.com\//i, '').replace(/\.git$/i, '').replace(/\/+$/, '');
  if (!repo) return '';
  if (repo.includes('/')) return /^[\w.-]+\/[\w.-]+$/.test(repo) ? repo : '';
  if (!/^[\w.-]+$/.test(repo)) return '';
  const me = await gh(token, 'GET', '/user');
  return `${me.login}/${repo}`;
}

async function ensureBranch(token, repo, wanted, direct) {
  const info = await gh(token, 'GET', `/repos/${repo}`);
  const def = info.default_branch;
  if (direct && (!wanted || wanted === def)) return { branch: def, def, created: false };
  const branch = wanted && wanted !== def ? wanted : `inhuman/${slug('update')}-${Date.now().toString(36)}`;
  try {
    await gh(token, 'GET', `/repos/${repo}/git/ref/heads/${encodeURIComponent(branch).replace(/%2F/g, '/')}`);
    return { branch, def, created: false };
  } catch (e) {
    if (e.status && e.status !== 404) throw e;
  }
  const head = await gh(token, 'GET', `/repos/${repo}/git/ref/heads/${def}`);
  await gh(token, 'POST', `/repos/${repo}/git/refs`, { ref: `refs/heads/${branch}`, sha: head.object.sha });
  return { branch, def, created: true };
}

async function commitFile(token, repo, path, content, message, branch) {
  let sha;
  try {
    const cur = await gh(token, 'GET', `/repos/${repo}/contents/${path.split('/').map(encodeURIComponent).join('/')}?ref=${encodeURIComponent(branch)}`);
    if (cur && cur.sha) sha = cur.sha;
  } catch (e) {
    if (e.status && e.status !== 404) throw e;
  }
  return gh(token, 'PUT', `/repos/${repo}/contents/${path.split('/').map(encodeURIComponent).join('/')}`, {
    message, content: Buffer.from(content, 'utf8').toString('base64'), branch, ...(sha ? { sha } : {})
  });
}

module.exports = async (req, res) => {
  cors(res);
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });

  const b = readBody(req);
  const token = String((b.github && b.github.token) || '').trim();
  const op = String(b.op || '');
  const done = (say, extra = {}) => res.status(200).json({ ok: true, say, ...extra });
  const fail = (say, extra = {}) => res.status(200).json({ ok: false, say, ...extra });

  if (!token) return fail('Add your GitHub token in Settings, Connect Apps first, sir.');

  try {
    if (op === 'repos') {
      const list = await gh(token, 'GET', '/user/repos?sort=updated&per_page=15');
      const names = list.map((r) => r.name);
      return done(names.length ? `Your latest repos are ${names.slice(0, 6).join(', ')}.` : 'You have no repos yet, sir.', { repos: list.map((r) => ({ name: r.full_name, private: r.private, updated: r.pushed_at })) });
    }

    if (op === 'create_repo') {
      const name = String(b.name || '').trim().replace(/\s+/g, '-');
      if (!/^[\w.-]{1,100}$/.test(name)) return fail('That repo name is not valid, sir.');
      const r = await gh(token, 'POST', '/user/repos', { name, description: String(b.description || '').slice(0, 300), private: b.private !== false, auto_init: true });
      return done(`Created ${r.private ? 'the private' : 'the public'} repo ${r.name}, sir.`, { url: r.html_url, repo: r.full_name });
    }

    const repo = await fullRepo(token, b.repo);
    if (!repo) return fail('Which repo, sir?');

    if (op === 'list') {
      const p = String(b.path || '').replace(/^\/+/, '');
      const items = await gh(token, 'GET', `/repos/${repo}/contents/${p.split('/').map(encodeURIComponent).join('/')}${b.ref ? `?ref=${encodeURIComponent(b.ref)}` : ''}`);
      const arr = Array.isArray(items) ? items : [items];
      return done(`${repo} has ${arr.length} item${arr.length === 1 ? '' : 's'} there: ${arr.slice(0, 10).map((i) => i.name).join(', ')}.`, { items: arr.map((i) => ({ name: i.name, type: i.type, size: i.size })) });
    }

    if (op === 'read') {
      const path = cleanPath(b.path);
      if (!path) return fail('Which file, sir?');
      const f = await gh(token, 'GET', `/repos/${repo}/contents/${path.split('/').map(encodeURIComponent).join('/')}${b.ref ? `?ref=${encodeURIComponent(b.ref)}` : ''}`);
      if (Array.isArray(f) || !f.content) return fail('That is a folder, not a file, sir.');
      const text = Buffer.from(f.content, 'base64').toString('utf8');
      return done(`Opened ${path}. It has ${text.split('\n').length} lines.`, { content: text.slice(0, 12000), truncated: text.length > 12000 });
    }

    if (op === 'issues' || op === 'prs') {
      const list = await gh(token, 'GET', op === 'issues' ? `/repos/${repo}/issues?state=open&per_page=10` : `/repos/${repo}/pulls?state=open&per_page=10`);
      const items = (op === 'issues' ? list.filter((i) => !i.pull_request) : list).map((i) => ({ number: i.number, title: i.title, url: i.html_url }));
      return done(items.length ? `${items.length} open ${op === 'issues' ? 'issues' : 'pull requests'}: ${items.slice(0, 5).map((i) => `number ${i.number}, ${i.title}`).join('; ')}.` : `No open ${op === 'issues' ? 'issues' : 'pull requests'}, sir.`, { items });
    }

    if (op === 'create_issue') {
      const title = String(b.title || '').trim().slice(0, 200);
      if (!title) return fail('What should the issue say, sir?');
      const i = await gh(token, 'POST', `/repos/${repo}/issues`, { title, body: String(b.body || '').slice(0, 20000) });
      return done(`Issue number ${i.number} is open on ${repo}, sir.`, { url: i.html_url });
    }

    if (op === 'create_pr') {
      const info = await gh(token, 'GET', `/repos/${repo}`);
      const head = String(b.head || '').trim();
      if (!head) return fail('Which branch should the pull request come from, sir?');
      const pr = await gh(token, 'POST', `/repos/${repo}/pulls`, { title: String(b.title || `Update from ${head}`).slice(0, 200), head, base: b.base || info.default_branch, body: String(b.body || 'Opened by InHuman AI.').slice(0, 20000) });
      return done(`Pull request number ${pr.number} is open, sir.`, { url: pr.html_url });
    }

    if (op === 'write_file' || op === 'code_file') {
      const path = cleanPath(b.path);
      if (!path) return fail('Which file name should I use, sir?');
      if (/^\.github\/workflows\//i.test(path)) return fail('I do not write workflow files, sir. They can run code with your secrets. Add that one by hand.');

      let content = String(b.content || '');
      if (op === 'code_file') {
        const task = String(b.task || '').trim().slice(0, 4000);
        if (!task) return fail('What should the code do, sir?');
        const cfg = await B.getConfig();
        const sub = await B.subjectFor({ token: b.token, deviceId: b.deviceId });
        const gate = await B.check(sub, cfg);
        if (!gate.ok) return fail(gate.message);
        const raw = await callAI({
          system: `You are an expert software engineer. Write the complete contents of the file "${path}" for the task below.
Reply with ONLY the raw file contents: no explanation, no markdown fences. Make it working, clean and commented where useful.`,
          history: [], message: task, keys: b.keys, adminKeys: cfg.keys, search: false, maxTokens: 6000, fast: false
        });
        content = stripFence(raw);
        await B.charge(sub, cfg, { credits: B.round2(cfg.costs.action * 2) }).catch(() => {});
      }
      if (!content.trim()) return fail('There was nothing to write, sir.');
      if (Buffer.byteLength(content, 'utf8') > MAX_FILE) return fail('That file is too big for me to commit, sir.');

      const { branch, def, created } = await ensureBranch(token, repo, String(b.branch || '').trim(), b.direct === true);
      const message = String(b.message || `Add ${path} (InHuman AI)`).slice(0, 200);
      const out = await commitFile(token, repo, path, content, message, branch);
      let prUrl = '';
      if (b.pr && branch !== def) {
        try {
          const pr = await gh(token, 'POST', `/repos/${repo}/pulls`, { title: message, head: branch, base: def, body: 'Opened by InHuman AI.' });
          prUrl = pr.html_url;
        } catch (e) { /* the commit still worked */ }
      }
      const where = branch === def ? `straight to ${def}` : `on the new branch ${branch}`;
      return done(`Done, sir. I committed ${path} to ${repo} ${where}${prUrl ? ', and opened a pull request' : ''}.`,
        { url: (out.content && out.content.html_url) || '', branch, pr: prUrl, created, lines: content.split('\n').length });
    }

    return fail('I do not know that GitHub command, sir.');
  } catch (e) {
    if (e && e.code === 'NO_KEY') return fail('I need an AI key to write code, sir. Add one in Connect Apps.');
    return fail(`GitHub said no, sir. ${String(e.message || e).slice(0, 160)}`);
  }
};
