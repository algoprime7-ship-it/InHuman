# InHuman AI // AETHER

A 3D halftone AI head assistant with a British voice, real AI answers, a music player,
and an **Android app** with a floating circle that listens for **"Hey Jarvis"** over any app.

---

## What's in this repo

```
index.html                         ← the web app (Vercel serves this)
README.md
.vercelignore                      ← stops Vercel publishing the Android code
api/
  ask.js                           ← AI brain: answers any question, decides phone actions
  yt.js                            ← YouTube search
android/                           ← the Android phone app (GitHub builds it for you)
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  app/
    build.gradle.kts
    inhuman.keystore.b64           ← signing key (so updates install over the old app)
    src/main/AndroidManifest.xml
    src/main/java/ai/inhuman/app/Core.kt
    src/main/java/ai/inhuman/app/MainActivity.kt
    src/main/java/ai/inhuman/app/OverlayService.kt
    src/main/res/drawable-nodpi/ic_logo.png
    src/main/res/values/strings.xml
    src/main/res/xml/accessibility_config.xml
.github/workflows/android.yml      ← builds the APK on GitHub
```

> **Keep this repo Private.** It contains the app's signing key.

---

## Part 1 — Web app + AI brain on Vercel

1. Upload everything to your GitHub repo, keeping the folders exactly as shown above.
2. On **vercel.com**: **Add New… → Project**, then import the repo. Framework Preset: **Other**. Leave Build and Output empty. Tap **Deploy**.
3. **Give the AI a brain (required for answering questions):**
   - Get a free key at **aistudio.google.com/apikey** (Google Gemini, includes Google Search for current news).
   - In Vercel, open your project → **Settings → Environment Variables** and add:
     - Name `GEMINI_API_KEY`, Value: your key.
   - Go to **Deployments**, tap ⋮ on the latest one, then **Redeploy**.
4. Check it works: open `https://your-project.vercel.app/api/ask` in Chrome. It should show `{"ok":true,"provider":"gemini"}`.

### Optional environment variables

| Name | What it does |
|------|--------------|
| `ANTHROPIC_API_KEY` | Use Claude instead of Gemini (paid). Used only if no Gemini key is set. |
| `GEMINI_MODEL` / `CLAUDE_MODEL` | Change the AI model. |
| `YT_API_KEY` | YouTube Data API v3 key for more reliable song search. |

---

## Part 2 — Android app (built by GitHub, no PC needed)

1. Once the `android/` folder and `.github/workflows/android.yml` are uploaded, open the repo's **Actions** tab.
   "Build InHuman Android app" starts on its own; you can also press **Run workflow**. It takes about 5–8 minutes.
2. When it shows a green ✓, open the repo's **Releases** page (on the main repo page). Open the newest **InHuman AI build** and tap **InHumanAI.apk** to download it.
3. Open the APK on your phone. If asked, allow Chrome to **install unknown apps**.
4. Every new build installs over the old app, so you don't need to uninstall first.

### First launch — setup screen

The app shows your device's name and type at the top. Then:

1. **Your InHuman web link:** paste your `https://your-project.vercel.app` link.
2. **"Hey Jarvis" key (optional):** sign up free at **console.picovoice.ai**, copy your **AccessKey** and paste it here.
   Without it, everything still works: just tap the floating circle to talk.
3. **Allow** each permission:
   - **Display over other apps** (required): shows the floating circle.
   - **Microphone** (required): lets InHuman hear commands.
   - **Notifications**: shows the "InHuman AI is online" notification, which Android needs to keep listening.
   - **Keep running in background**: stops the phone from closing InHuman.
   - **Accessibility** (optional): lets InHuman press Home, Back and Recent apps, and lock the screen.
     If Android says **"Restricted setting"**: tap **App info**, tap ⋮ (top right), choose **Allow restricted settings**, then switch it on.
4. Tap **Start InHuman**.

### The floating circle

- **Drag** it anywhere on the screen. It remembers where you leave it.
- **Say "Hey Jarvis"** (or **tap** it). It answers **"Yes sir"**, the circle pulses blue, and you say your command.
- Answers appear in a **card under the circle** and are spoken aloud. Tap the card to close it.
- **Long-press** the circle to open the full InHuman app.
- To turn the circle on or off, use **Sidebar → System Devices → InHuman Floating Assistant**, or the **Settings** button on the notification.

### Voice commands

| Say | What happens |
|-----|--------------|
| "Open Instagram" / "Open WhatsApp" / "Open Camera" | Opens that app |
| "Open YouTube and play Starboy" | Opens YouTube and plays the song |
| "Play TRAPJUMP on YouTube" | Same as above |
| "Open YouTube and search for car reviews" | Shows YouTube search results |
| "Did MrBeast drop new videos this month?" | AI searches the web and answers in the card |
| "What's the weather in Johannesburg?" / any question | AI answers |
| "Go home" / "Go back" / "Recent apps" / "Lock the screen" | Needs Accessibility switched on |

Inside the app's own chat, **"Play [song]"** still plays in the built-in music player.

---

## Web app features

- **Loading screen:** your logo spins in 3D for 7 seconds, glitches, then "InHuman AI" types out and glitches with it. Tap to skip.
- **Anger Mode** is now a switch in the **sidebar**.
- **System Devices** shows your real phone name, model and Android version.
- **Music player:** YouTube first, then free full-length songs from Audius, then 30-second iTunes previews.
  The **Skip ad ⏭** button appears when a YouTube ad seems to be playing, and switches to an ad-free copy when one exists.

---

## Limits

- **iPhone can't do this.** iOS doesn't allow apps to float over other apps or listen in the background.
  On iPhone, the web app works in Safari, but there's no floating circle.
- **Google Play:** apps using Accessibility and always-on microphones face strict review. Installing the APK directly (as above) is fine.
- **Picovoice free tier** is for personal use with a limited number of devices. Check their terms before giving the app to lots of users.
- Your `/api/ask` link is public. Anyone who finds it uses your AI quota. Gemini's free tier limits the damage. Watch usage if you switch to a paid key.

---

## Troubleshooting

| Problem | Fix |
|--------|-----|
| AI says "My brain is not connected" | Add `GEMINI_API_KEY` in Vercel and **Redeploy**. |
| GitHub build failed (red ✗) | Open the run, tap the failed step, and copy the error message to get help. |
| "App not installed" | Uninstall any older InHuman build made before the signing key was added, then install again. |
| "Hey Jarvis" does nothing | Check the AccessKey in Settings (notification → Settings), and make sure the notification says "online". |
| Circle disappears after a while | Allow **Keep running in background**. On Samsung, also go to Settings → Battery → Background usage limits and add InHuman to **Never sleeping apps**. |
| Error 153 on YouTube in the browser | Open your `.vercel.app` link, not a downloaded file. |
| 404 on Vercel | The web app file must be named exactly `index.html`, in the main folder. |
