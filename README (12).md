# InHuman AI // AETHER

A 3D halftone AI head assistant with a British voice, real AI answers, a music player,
and an **Android app** with a floating circle that wakes on your own trigger phrase (default **"hey jarvis"**),
controls your phone, sends messages and emails, places MT5 trades, and keeps music playing after the app is closed.

---

## What's in this repo

```
index.html                         ← the web app (Vercel serves this)
player.html                        ← small YouTube player used by the floating circle
package.json                       ← tells Vercel to install nodemailer (for emails)
vercel.json                        ← gives the AI functions up to 60 seconds
README.md
.vercelignore                      ← stops Vercel publishing the Android code
api/
  _ai.js                           ← shared AI code (Gemini / Claude / ChatGPT)
  _yt.js                           ← shared music search code
  ask.js                           ← AI brain: answers + decides phone actions
  analyze.js                       ← MT5: picks BUY or SELL before trades
  email.js                         ← sends emails from the user's own account
  music.js                         ← song search for the floating player
  yt.js                            ← YouTube search
android/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  app/
    build.gradle.kts
    inhuman.keystore.b64
    src/main/AndroidManifest.xml
    src/main/java/ai/inhuman/app/Core.kt
    src/main/java/ai/inhuman/app/Brain.kt
    src/main/java/ai/inhuman/app/MainActivity.kt
    src/main/java/ai/inhuman/app/Settings.kt
    src/main/java/ai/inhuman/app/OverlayService.kt
    src/main/java/ai/inhuman/app/Accessibility.kt
    src/main/java/ai/inhuman/app/WakeWord.kt
    src/main/res/drawable-nodpi/ic_logo.png
    src/main/res/values/strings.xml
    src/main/res/xml/accessibility_config.xml
    src/main/res/xml/file_paths.xml
.github/workflows/android.yml      ← builds the APK on GitHub
```

> **Keep this repo Private.** It contains the app's signing key.

---

## Part 1 — Web app + AI brain on Vercel

1. Upload everything to GitHub, keeping the folders exactly as shown above.
2. On **vercel.com**: **Add New… → Project**, then import the repo. Framework Preset: **Other**. Leave Build and Output empty. Tap **Deploy**.
3. **AI brain:** get a free key at **aistudio.google.com/apikey**. In Vercel, go to **Settings → Environment Variables** and add `GEMINI_API_KEY`, then **Redeploy**.
4. Check it: open `https://your-project.vercel.app/api/ask`. It should show `{"ok":true,"provider":"gemini"}`.

| Optional variable | What it does |
|---|---|
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | Use Claude or ChatGPT instead (if no Gemini key is set) |
| `YT_API_KEY` | YouTube Data API v3 key for more reliable song search |

---

## Part 2 — Android app

1. After uploading, open **Actions**. "Build InHuman Android app" runs by itself (5–8 minutes).
2. Once it's ✅, open **Releases**, then the newest build, then **InHumanAI.apk**, and install it.
3. New builds install over the old app.

### Install setup (first launch only)

The phone's setup screen only handles the install basics. You can reopen it from the notification's **Setup** button,
or from **Settings → Configurations → Phone setup & permissions** in the app.
- Your Vercel link
- **Allow** buttons: Display over other apps (required) · Microphone (required) · **Contacts** (text/call/email by name) ·
  **Phone calls** · Notifications · Keep running in background ·
  **Accessibility** (presses Send in WhatsApp/Instagram/Messenger, plus Home/Back/Lock).
  If Android says "Restricted setting": tap **App info → ⋮ → Allow restricted settings** first.

### App Settings (home page → sidebar → Settings)

**Configurations**
- **Trigger phrase:** any phrase, e.g. `hey jarvis` or `hey parvis`. Tap **Save** and the floating circle listens for it.
  The voice engine (about 40 MB) downloads once, the first time the floating assistant starts. Use Wi-Fi.
- **InHuman answers with:** e.g. `Yes sir`
- **Your name:** used to sign emails

**Emails**
- Add any number of **personal** and **business** emails, each with its **app password**
  (Gmail: turn on 2-Step Verification, then go to **myaccount.google.com/apppasswords**). Outlook, Yahoo and iCloud are set up automatically.
  Other business domains: enter the SMTP server and port (Google Workspace uses `smtp.gmail.com`).
- **Email groups:** e.g. `clients`, with a list of addresses. "Send a business proposal to my clients" emails all of them.

**Connect Apps**
- **Gemini / Claude / ChatGPT keys:** the user's own key is used instead of the shared one. "Answer with" picks which.
- **GitHub token:** answers questions about your repos and recent activity.
- **MetaTrader 5:** bridge link, login, password, server, then **Connect MT5**. Only the returned token is saved.
  Also set the default lot size and the symbol suffix (e.g. `m` for `XAUUSDm`).

In the phone app, these settings are saved on the phone and the floating circle uses them too.
In a normal browser, they're saved in that browser, and only the AI keys and GitHub token are used.

---

## Part 3 — Accounts, credits and the admin page

**1. Turn on the database (once).** Vercel → your project → Storage → Upstash for Redis → Create → Connect to the project → Redeploy. Without it, everything still works free with no limits and no accounts.

**2. Make your super admin (once).** Open `your-site.vercel.app/admin` → Sign up. After the first admin exists, the sign-up form disappears for good. Log in from then on.

**Admin pages**
- Dashboard: total clients, paid clients, today's income and visits, plus the spoken briefing (sign-ups, paid per plan, income from subscriptions vs credits, trading profit if a bridge is set).
- Subscriptions: search by name or email, tap the Total / Paid / Free boxes to filter. Each client card has Mark as paid, Approve credits (shows when they tapped "I've paid") and Decline (back to free).
- Configurations: plan prices, credits and PayPal links, free limits, AI keys and MT5 bridge. Your keys show to clients as dots. Leave a key field empty to keep it, type `-` to remove it.

**Free version (no account needed)**
- 50 messages a day (text or voice, app or floating circle — 1 each)
- 100 credits: each action 2.25, each song 5 (playlist or not), speech 0.75 per second
- When a limit is hit, everything stops for 7 hours with a countdown.

**Paid plans (PayPal)** — Starter $15 / 500 credits, Pro $25, Executive $50, S Pro $100. Client pays with the link, taps "I've paid", you approve it in Subscriptions, and the credits land on their account. PayPal links don't notify the site, so approval is manual.

Client accounts have no password: one email = one account. A repeated email is refused.

## Voice commands

Say your trigger phrase (or tap the circle). InHuman answers "Yes sir", then you say the command.
The same commands also work in the app's chat box.

| Say | What happens |
|---|---|
| "Open Instagram" / "Open WhatsApp" | Opens the app |
| "Text Mom on WhatsApp tell her I'm on my way" | Opens the chat and presses Send* |
| "Send a voice note to John" | Records until you stop talking (or tap the circle), then sends it on WhatsApp* |
| "Message @thabo_sa on Instagram saying are we still on for tonight" | Opens the DM, types it and presses Send* |
| "Message thabo.m on Facebook saying …" | Same, in Messenger* |
| "Call John" / "Call 082 123 4567" | Dials |
| "Send an email to sarah@company.com saying the meeting moved to 3pm" | Sends from your first email |
| "Use my business email to send a proposal to my clients" | AI writes the proposal and sends it to everyone in the "clients" group |
| "Open 5 trades on gold" / "Open 3 trades on Bitcoin" | Checks the direction with AI, then opens the trades through your MT5 bridge |
| "Buy 2 trades on EURUSD" | Opens 2 buy trades (no analysis needed) |
| "Play Starboy" | Plays on the floating player |
| "Play Starboy on YouTube Music" / "on Spotify" / "on YouTube" | Opens that app and plays it |
| "Pause music" / "Next song" / "Stop music" | Controls the floating player |
| "Did MrBeast drop new videos this month?" / any question | AI searches the web and answers in the card |
| "Go home" / "Go back" / "Lock the screen" | Needs Accessibility |

\* Pressing **Send** automatically needs Accessibility switched on. Without it, InHuman fills everything in and you tap Send.

---

## Music that keeps playing

- Play a song in the app, then close or leave the app: the song carries on from the same second on the **floating player**, and the circle's logo spins while music plays.
- **Audius songs** play as pure audio in the background.
- **YouTube songs** play in a small video window. YouTube requires its video to stay visible, so it can't be audio-only. Drag the window by its title. ⏸ ⏭ ✕ control it.
- Open the app again and the song moves back into the app.
- Every song played, in the app or on the floating player, is saved to the **Recently Played** playlist.

---

## Important to know

- **Messages and trades go out immediately.** There is no "are you sure?" step, so a misheard name or symbol is acted on as heard.
- **MT5 analysis is an AI guess** from current news and price action, not a proven strategy. It can be wrong and real money is at risk. Start on a **demo account**.
- **Your bridge's /ordersend:** the app sends `token, symbol, type (BUY/SELL), action, volume, lot, comment` with the token also in the `Authorization: Bearer` header.
  If your bridge uses different field names, change them in `Brain.kt` → `trade()`.
  Login uses `/connect` with `login, password, server` and reads back `token`.
- **Email app passwords and API keys** are stored on the phone, in the app's private storage. Email passwords travel to your Vercel server only for each send, over HTTPS, and are never stored there.
- **Instagram and Facebook** change their screens often, so auto-Send there can stop working after their updates. WhatsApp is the most reliable.
- **iPhone can't do any of this** (no floating apps or background listening on iOS).
- **Google Play** strictly reviews apps using Accessibility and always-on microphones. Installing the APK directly is fine.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| AI says "My brain is not connected" | Add `GEMINI_API_KEY` in Vercel and **Redeploy**, or add your own key in Connect Apps |
| Trigger phrase does nothing | Check the circle's card says "Voice trigger ready". If the download failed, go to Settings → Configurations → **Save** to retry. |
| Emails fail with "login failed" | Use an **app password**, not your normal password |
| "I couldn't find Mom in your contacts" | Allow Contacts in Setup, and say the name as it's saved |
| WhatsApp opens but doesn't send | Switch on Accessibility (see Setup) |
| No trades opened | Check Connect Apps → MT5 status, the symbol suffix, and that your bridge is online |
| Music stops when app closes | The floating assistant must be on (notification says "online") |
| GitHub build failed (red ✗) | Open the run, tap the failed step, and send a screenshot of the red lines |
