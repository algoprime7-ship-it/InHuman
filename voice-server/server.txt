"""
InHuman voice server (runs on your DigitalOcean droplet).

Every reply the app speaks is sent here; Chatterbox (MIT licence, free for commercial use) speaks it in the
voice of that face, cloned from a short sample you uploaded at  http://<droplet-ip>:8000/admin?key=<key>

  POST /tts            {"text": "...", "face": "jarvis"}   header X-Key  ->  audio/mpeg
  GET  /admin?key=...  phone page: upload a voice sample (audio or video) for each face
  GET  /health         is it running, which voices are ready
"""
import hashlib
import os
import subprocess
import tempfile
import threading
from pathlib import Path

import torch
import torchaudio as ta
from fastapi import FastAPI, File, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from pydantic import BaseModel

ROOT = Path(os.environ.get("VOICE_ROOT", "/opt/inhuman-voice"))
VOICES = ROOT / "voices"
CACHE = ROOT / "cache"
KEY = os.environ.get("VOICE_KEY", "")
FACES = ["aether", "jarvis", "ultron", "him", "matrix", "sentinel", "specter", "friday"]
# per face: (exaggeration, cfg_weight) for the original model; ignored by Turbo
STYLE = {
    "aether": (0.45, 0.5), "jarvis": (0.4, 0.5), "ultron": (0.6, 0.35), "him": (0.55, 0.35),
    "matrix": (0.5, 0.45), "sentinel": (0.7, 0.3), "specter": (0.55, 0.45), "friday": (0.55, 0.5),
}
VOICES.mkdir(parents=True, exist_ok=True)
CACHE.mkdir(parents=True, exist_ok=True)
torch.set_num_threads(max(1, os.cpu_count() or 1))

# ---------------- model (loaded once) ----------------
MODEL = None
KIND = ""
LOCK = threading.Lock()


def load_model():
    global MODEL, KIND
    errors = []
    try:  # fastest on CPU
        from chatterbox.tts_turbo import ChatterboxTurboTTS
        try:
            MODEL = ChatterboxTurboTTS.from_pretrained(device="cpu", nano=True)
            KIND = "turbo-nano"
        except TypeError:
            MODEL = ChatterboxTurboTTS.from_pretrained(device="cpu")
            KIND = "turbo"
        return
    except Exception as e:  # older package without Turbo
        errors.append(f"turbo: {e}")
    from chatterbox.tts import ChatterboxTTS
    MODEL = ChatterboxTTS.from_pretrained(device="cpu")
    KIND = "original"
    print("Loaded original Chatterbox:", errors)


load_model()
print(f"Chatterbox ready ({KIND}) on {torch.get_num_threads()} CPU threads")

app = FastAPI()


def check_key(k):
    if not KEY or k != KEY:
        raise HTTPException(status_code=401, detail="bad key")


def voice_file(face: str) -> Path:
    f = VOICES / f"{face}.wav"
    if f.exists():
        return f
    # faces without their own sample borrow the closest one
    for alt in {"him": "ultron", "ultron": "him", "sentinel": "ultron", "aether": "jarvis", "jarvis": "aether",
                "matrix": "jarvis", "specter": "jarvis"}.get(face, "jarvis"), "jarvis", "aether":
        if (VOICES / f"{alt}.wav").exists():
            return VOICES / f"{alt}.wav"
    raise HTTPException(status_code=404, detail=f"No voice sample for {face}. Upload one at /admin")


class Req(BaseModel):
    text: str
    face: str = "aether"


@app.get("/health")
def health():
    return {"ok": True, "model": KIND, "voices": sorted(p.stem for p in VOICES.glob("*.wav"))}


@app.post("/tts")
def tts(r: Req, x_key: str = Header(default="")):
    check_key(x_key)
    text = " ".join(r.text.split())[:600]
    face = r.face if r.face in FACES else "aether"
    if not text:
        raise HTTPException(status_code=400, detail="no text")
    ref = voice_file(face)
    tag = hashlib.sha1(f"{face}|{ref.stat().st_mtime}|{KIND}|{text}".encode()).hexdigest()
    out = CACHE / f"{tag}.mp3"
    if not out.exists():
        with LOCK:  # one at a time: the CPU is the limit
            if KIND == "original":
                ex, cfg = STYLE.get(face, (0.5, 0.5))
                wav = MODEL.generate(text, audio_prompt_path=str(ref), exaggeration=ex, cfg_weight=cfg)
            else:
                wav = MODEL.generate(text, audio_prompt_path=str(ref))
            with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                ta.save(tmp.name, wav, MODEL.sr)
                subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-ac", "1", "-b:a", "96k", str(out)], check=True)
                os.unlink(tmp.name)
        trim_cache()
    return FileResponse(out, media_type="audio/mpeg", headers={"Cache-Control": "public, max-age=604800"})


def trim_cache(limit=3000):
    files = sorted(CACHE.glob("*.mp3"), key=lambda p: p.stat().st_mtime)
    for p in files[:-limit]:
        try:
            p.unlink()
        except OSError:
            pass


# ---------------- upload page (open on your phone) ----------------
@app.post("/voices/{face}")
async def upload(face: str, file: UploadFile = File(...), key: str = Query(default="")):
    check_key(key)
    if face not in FACES:
        raise HTTPException(status_code=400, detail="unknown face")
    raw = await file.read()
    if len(raw) > 80 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="file too big (max 80 MB)")
    with tempfile.NamedTemporaryFile(delete=False) as tmp:
        tmp.write(raw)
        src = tmp.name
    dst = VOICES / f"{face}.wav"
    # audio or video in, clean 20-second mono wav out
    p = subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-vn", "-ac", "1", "-ar", "24000",
                        "-af", "silenceremove=start_periods=1:start_threshold=-45dB,loudnorm", "-t", "20", str(dst)],
                       capture_output=True, text=True)
    os.unlink(src)
    if p.returncode != 0 or not dst.exists():
        raise HTTPException(status_code=400, detail="Could not read that file: " + p.stderr[-200:])
    return {"ok": True, "face": face}


@app.get("/voices/{face}.wav")
def listen(face: str, key: str = Query(default="")):
    check_key(key)
    return FileResponse(voice_file(face), media_type="audio/wav")


@app.get("/admin", response_class=HTMLResponse)
def admin(key: str = Query(default="")):
    check_key(key)
    have = {p.stem for p in VOICES.glob("*.wav")}
    rows = "".join(f"""
      <div class="card"><div class="top"><b>{f.capitalize()}</b><span class="{ 'ok' if f in have else 'no' }">{'✓ ready' if f in have else 'no voice yet'}</span></div>
      {'<audio controls src="/voices/' + f + '.wav?key=' + key + '"></audio>' if f in have else ''}
      <input type="file" accept="audio/*,video/*" data-face="{f}"><div class="msg" id="m-{f}"></div></div>""" for f in FACES)
    return f"""<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>InHuman voices</title><style>
body{{background:#05070c;color:#e5e7eb;font:15px system-ui;margin:0;padding:16px}}h1{{font-size:18px}}
.card{{border:1px solid #333;border-radius:14px;padding:12px;margin:10px 0;background:#0c111b}}
.top{{display:flex;justify-content:space-between;margin-bottom:8px}}.ok{{color:#34d399}}.no{{color:#94a3b8}}
audio{{width:100%;margin-bottom:8px}}.msg{{font-size:12px;color:#94a3b8;margin-top:6px}}p{{color:#94a3b8;font-size:13px}}
</style></head><body><h1>InHuman voices · {KIND}</h1>
<p>For each face pick a clear recording (10–20 seconds, one person talking, no music). Audio or video both work.
Only use voices you have permission to use.</p>{rows}
<script>
document.querySelectorAll('input[type=file]').forEach(i=>i.onchange=async()=>{{
 const f=i.files[0]; if(!f) return; const m=document.getElementById('m-'+i.dataset.face); m.textContent='Uploading…';
 const fd=new FormData(); fd.append('file',f);
 const r=await fetch('/voices/'+i.dataset.face+'?key={key}',{{method:'POST',body:fd}});
 const d=await r.json().catch(()=>({{}}));
 m.textContent=r.ok?'Saved ✓ (reloading)':'Error: '+(d.detail||r.status); if(r.ok) setTimeout(()=>location.reload(),900);
}});
</script></body></html>"""
