#!/usr/bin/env bash
# InHuman voice server: paste this ONE line into your droplet's console:
#   curl -fsSL https://inhuman56.vercel.app/voice-server/install.sh | sudo bash
set -e
SITE="${SITE:-https://inhuman56.vercel.app}"
DIR=/opt/inhuman-voice
echo "== InHuman voice server: installing (10-20 minutes) =="
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3 python3-venv python3-pip ffmpeg curl ufw
# swap helps the model load on 8 GB droplets
if ! swapon --show | grep -q swapfile; then
  fallocate -l 6G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
mkdir -p $DIR/voices $DIR/cache
curl -fsSL "$SITE/voice-server/server.py" -o $DIR/server.py
python3 -m venv $DIR/venv
$DIR/venv/bin/pip install --upgrade pip wheel
# CPU-only PyTorch (much smaller than the GPU build)
$DIR/venv/bin/pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
$DIR/venv/bin/pip install chatterbox-tts fastapi "uvicorn[standard]" python-multipart
# chatterbox may have pulled the big GPU build of torch: swap in the CPU build of the same version
TV=$($DIR/venv/bin/python -c "import torch;print(torch.__version__.split('+')[0])")
TAV=$($DIR/venv/bin/python -c "import torchaudio;print(torchaudio.__version__.split('+')[0])")
$DIR/venv/bin/pip install --force-reinstall --no-deps "torch==$TV" "torchaudio==$TAV" --index-url https://download.pytorch.org/whl/cpu || true
$DIR/venv/bin/pip cache purge >/dev/null 2>&1 || true
# secret key (kept if you run this again)
if [ ! -f $DIR/key ]; then head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32 > $DIR/key; fi
KEY=$(cat $DIR/key)
cat > /etc/systemd/system/inhuman-voice.service <<UNIT
[Unit]
Description=InHuman voice server
After=network.target
[Service]
Environment=VOICE_KEY=$KEY
Environment=VOICE_ROOT=$DIR
Environment=HF_HOME=$DIR/hf
WorkingDirectory=$DIR
ExecStart=$DIR/venv/bin/uvicorn server:app --host 0.0.0.0 --port 8000 --workers 1
Restart=always
RestartSec=5
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now inhuman-voice
ufw allow 22/tcp >/dev/null || true
ufw allow 8000/tcp >/dev/null || true
yes | ufw enable >/dev/null || true
IP=$(curl -fsS https://api.ipify.org || hostname -I | awk '{print $1}')
echo "Waiting for the voice model to download and load (first time takes a few minutes)…"
for i in $(seq 1 120); do
  if curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; then break; fi
  sleep 10
done
echo ""
echo "================= DONE ================="
echo "Upload voices on your phone:  http://$IP:8000/admin?key=$KEY"
echo ""
echo "Add these on Vercel (Settings > Environment Variables), then redeploy:"
echo "  VOICE_SERVER_URL = http://$IP:8000"
echo "  VOICE_SERVER_KEY = $KEY"
echo "========================================"
