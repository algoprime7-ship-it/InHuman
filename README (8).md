# InHuman AI // AETHER

A 3D halftone AI head assistant with a British voice, a chat box, and a built-in music player.
Say or type **"Play [song name]"** and AETHER finds the song and plays it from start to finish.

The whole app is a single file: `index.html`.

---

## Files in this repo

| File | What it is |
|------|------------|
| `index.html` | The full app (rename `inhuman_ai_music_fixed.html` to this) |
| `README.md` | This file |

---

## Deploy on Vercel (works from a phone)

1. Upload `index.html` and `README.md` to a GitHub repo, in the main folder (not inside a subfolder).
2. Go to **vercel.com** and sign in with GitHub.
3. Tap **Add New… → Project**.
4. Pick this repo and tap **Import**.
5. Leave the settings as they are:
   - **Framework Preset:** Other
   - **Build Command:** leave empty
   - **Output Directory:** leave empty
6. Tap **Deploy**.
7. Vercel gives you a link like `your-project.vercel.app`. Open that link in Chrome.

Every time you upload a new `index.html` to GitHub, Vercel updates the site automatically within a minute or so.

**Tip:** In Chrome, tap ⋮ → **Add to Home screen** so it opens like a normal app.

---

## Why it must be hosted

YouTube only plays songs inside pages that come from a real web link (`https://…`).
If you open the file straight from your phone's Downloads (`content://…` or `file://…`), YouTube shows
**Error 153 – Video player configuration error** for every song.

When it's opened from Downloads, the app skips YouTube and tries free full-length sources instead.

---

## How music playback works

1. **YouTube** comes first: full songs, inside a small visible player.
2. **Audius** is a free music site with full-length songs. It's used when YouTube can't play the song.
3. **iTunes preview** is a 30-second clip, used only as a last resort. The app tells you when this happens.

When a song ends, the next song in the queue plays automatically.

### Player controls (bar at the top)
- **Tap** to pause or resume
- **Swipe right** for the next song
- **Swipe left** for the previous song

### Skip ad button
When a YouTube ad seems to be playing, a **Skip ad ⏭** bar appears under the video.
Tapping it looks for an ad-free full copy of the same song on Audius and plays that instead.
If no copy exists, tap YouTube's own **Skip** on the video when it appears.

The app can't press YouTube's real Skip button, because YouTube runs in a locked frame.
YouTube's rules also require the video to stay visible, so YouTube songs aren't audio-only.

---

## Chat commands

| Say / type | What happens |
|-----------|--------------|
| `Play Blinding Lights` | Finds and plays the song |
| `Play Favorites` | Plays a whole playlist |
| `Create playlist Chill and add Starboy, Blinding Lights` | Makes a playlist with those songs |
| `Add Starboy to Chill` | Adds a song to a playlist |
| `Introduce yourself` / `System diagnostics` / `What time is it` | AETHER answers out loud |

You can also upload your own MP3, WAV, OGG, M4A or FLAC files with the **+** button.

---

## Optional: YouTube API key (better search)

Song search uses free public servers, which are sometimes slow or down.
For more reliable search, get a free **YouTube Data API v3** key from Google Cloud Console.
Then paste it into this line near the music section of `index.html`:

```js
const YT_API_KEY = 'PASTE_YOUR_KEY_HERE';
```

In Google Cloud, restrict the key to your Vercel domain (HTTP referrers) so other people can't use it.

---

## Troubleshooting

| Problem | Fix |
|--------|-----|
| **Error 153** | You opened the file from Downloads. Open your `.vercel.app` link instead. |
| **Error 101 / 150** | The video's owner blocks playing on other sites. The app tries a free copy automatically. |
| **"Track not found"** | No playable copy exists on YouTube embeds, Audius or iTunes. Try a different spelling, or upload the MP3. |
| **No sound after "Now playing"** | Tap the player bar once. Browsers sometimes need a tap before playing audio. |
| **Voice is not British / male** | It uses your phone's text-to-speech voices. Install an English (UK) voice in your phone's settings. |
| **404 on Vercel** | Make sure the file is named exactly `index.html` and is in the main folder of the repo. |
