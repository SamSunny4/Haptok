# Haptok Backend — AI Video-to-Haptics Server

> Transform any video into cinematic haptic feedback with AI-powered analysis.

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                   Android App (Client)                    │
│  • Uploads video to backend                              │
│  • Receives haptic timeline JSON                         │
│  • Plays synchronized haptics during video playback      │
└────────────────────────┬─────────────────────────────────┘
                         │  HTTP REST + WebSocket
                         ▼
┌──────────────────────────────────────────────────────────┐
│              FastAPI Server (Port 8000)                   │
│  • POST /api/v1/videos/upload                            │
│  • GET  /api/v1/jobs/{id}/status                         │
│  • GET  /api/v1/jobs/{id}/haptics                        │
│  • WS   /ws/jobs/{id}                                    │
└────────────────────────┬─────────────────────────────────┘
                         │  Celery Task Queue
                         ▼
┌──────────────────────────────────────────────────────────┐
│              Processing Pipeline (Worker)                 │
│                                                          │
│  1. FFmpeg     → Extract audio (WAV) + keyframes (JPG)   │
│  2. Audio      → Onset detection, FFT, event classify    │
│  3. Video      → Optical flow, camera shake, scene cuts  │
│  4. Fusion     → Merge audio + video features            │
│  5. Generator  → Produce haptic timeline JSON            │
└────────────────────────┬─────────────────────────────────┘
                         │
                    Redis (Queue + Cache)
```

## Prerequisites

| Component | Minimum | Recommended |
|-----------|---------|-------------|
| **OS** | Ubuntu 20.04+ | Ubuntu 22.04 |
| **RAM** | 8 GB | 16+ GB |
| **Disk** | 10 GB free | 20+ GB |
| **Docker** | v24+ | Latest |
| **Docker Compose** | v2+ | Latest |
| **GPU** | None (CPU works) | AMD RX 6600+ or NVIDIA GPU |

---

## Quick Start (CPU Mode)

This gets you running in under 5 minutes, no GPU setup required.

### 1. Copy the backend to your server

```bash
# Option A: From the project directory
scp -r backend/ user@your-server:~/haptok-backend/

# Option B: Clone the repo
git clone <your-repo-url>
cd Haptok/backend
```

### 2. Start the services

```bash
cd ~/haptok-backend  # or wherever you copied to
docker compose up --build -d
```

This starts 3 containers:
- **haptok-api** — FastAPI server on port 8000
- **haptok-worker** — Celery worker processing videos
- **haptok-redis** — Redis for job queue and status

### 3. Verify it's running

```bash
# Health check
curl http://localhost:8000/health

# Expected response:
# {"status":"healthy","device":"cpu","redis":"connected"}
```

### 4. Open API documentation

Visit: **http://localhost:8000/docs**

This shows the interactive Swagger UI where you can test all endpoints.

### 5. Test with a video

```bash
# Upload a test video (replace with your file)
curl -X POST http://localhost:8000/api/v1/videos/upload \
  -F "file=@test_video.mp4" \
  -F 'device_profile={}'

# Response: {"job_id": "abc-123-...", "status": "queued"}

# Poll status
curl http://localhost:8000/api/v1/jobs/abc-123-.../status

# When complete, get haptic timeline
curl http://localhost:8000/api/v1/jobs/abc-123-.../haptics
```

---

## AMD ROCm GPU Setup (RX 6600)

GPU acceleration speeds up audio/video analysis ~3-5x. These steps are for your AMD RX 6600 on Ubuntu.

### 1. Install ROCm Drivers

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install prerequisites
sudo apt install -y wget gnupg2

# Add AMD ROCm repository (ROCm 6.x)
sudo mkdir --parents --mode=0755 /etc/apt/keyrings
wget https://repo.radeon.com/rocm/rocm.gpg.key -O - | \
  gpg --dearmor | sudo tee /etc/apt/keyrings/rocm.gpg > /dev/null

echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/rocm.gpg] https://repo.radeon.com/rocm/apt/6.0.2 jammy main" | \
  sudo tee /etc/apt/sources.list.d/rocm.list

sudo apt update

# Install ROCm runtime and libraries
sudo apt install -y rocm-dev rocm-libs

# Add your user to the required groups
sudo usermod -a -G video,render $USER

# IMPORTANT: Log out and back in for group changes to take effect
```

### 2. Verify ROCm Installation

```bash
# Check GPU is detected
rocm-smi

# Expected output should show your RX 6600
# If it says "No GPU detected", reboot and try again
```

### 3. Enable GPU in Docker Compose

Edit `docker-compose.yml` and uncomment the GPU lines for both `app` and `worker` services:

```yaml
services:
  app:
    # ...
    devices:
      - /dev/kfd:/dev/kfd
      - /dev/dri:/dev/dri
    group_add:
      - video
      - render

  worker:
    # ...
    devices:
      - /dev/kfd:/dev/kfd
      - /dev/dri:/dev/dri
    group_add:
      - video
      - render
```

### 4. Update Dockerfile for ROCm

In `Dockerfile`, change the base image:

```dockerfile
# Change this line:
FROM python:3.11-slim AS base

# To this:
FROM rocm/pytorch:rocm6.0.2_ubuntu22.04_py3.10_pytorch_2.1.2 AS base
```

### 5. Install ROCm-compatible PyTorch

Add to `requirements.txt` or install separately:

```bash
# Inside the container (or add to Dockerfile)
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/rocm6.0
```

### 6. Rebuild and start

```bash
docker compose down
docker compose up --build -d

# Verify GPU is detected
curl http://localhost:8000/health
# Should show: {"status":"healthy","device":"rocm","redis":"connected"}
```

---

## Connecting the Android App

### Find Your Server IP

```bash
# On the server
ip addr show | grep "inet " | grep -v 127.0.0.1
# Example output: inet 192.168.1.100/24 ...
```

### Configure the App

1. Open the Haptok app on your Android device
2. Go to **Settings** (gear icon on home screen)
3. Set **Server URL** to: `http://192.168.1.100:8000` (use your server's IP)
4. Tap **Save**

> **Important**: Both devices must be on the same local network. If using a firewall, ensure port 8000 is open:
> ```bash
> sudo ufw allow 8000
> ```

### For Emulator Testing

The default server URL (`http://10.0.2.2:8000`) automatically routes to your host machine's `localhost` from the Android emulator.

---

## API Reference

### Upload Video

```http
POST /api/v1/videos/upload
Content-Type: multipart/form-data
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | File | Yes | Video file (mp4, mov, mkv, webm, avi) |
| `device_profile` | String | No | JSON with device haptic capabilities |

**Response** (202 Accepted):
```json
{
  "job_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "queued"
}
```

### Get Job Status

```http
GET /api/v1/jobs/{job_id}/status
```

**Response**:
```json
{
  "job_id": "550e8400-...",
  "status": "processing",
  "progress": 0.45,
  "message": "Analyzing audio...",
  "error": null,
  "result_ready": false
}
```

Status values: `queued`, `processing`, `complete`, `failed`

### Get Haptic Timeline

```http
GET /api/v1/jobs/{job_id}/haptics
```

Returns the full haptic timeline JSON (only available when status is `complete`).

### WebSocket Updates

```
WS /ws/jobs/{job_id}
```

Streams real-time JSON status updates. Connection closes when job completes or fails.

### Health Check

```http
GET /health
```

---

## Configuration

All settings are configurable via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_URL` | `redis://redis:6379/0` | Redis connection URL |
| `CELERY_BROKER_URL` | `redis://redis:6379/0` | Celery broker URL |
| `CELERY_RESULT_BACKEND` | `redis://redis:6379/1` | Celery result backend |
| `UPLOAD_DIR` | `/tmp/haptok/uploads` | Video upload directory |
| `MODELS_DIR` | `/opt/haptok/models` | AI model storage |
| `MAX_VIDEO_DURATION` | `240` | Max video length in seconds (4 min) |
| `MAX_FILE_SIZE` | `524288000` | Max upload size in bytes (500 MB) |
| `HOST` | `0.0.0.0` | API server bind address |
| `PORT` | `8000` | API server port |

---

## AI Model Downloads (Optional)

The system works out of the box with **rule-based analysis** that produces good haptic results. For enhanced AI-powered analysis:

```bash
bash download_models.sh
```

This will guide you through downloading:
- **AST** — Audio event classification (~300MB)
- **SlowFast** — Video action recognition (~200MB)
- **RAFT** — High-precision optical flow (Phase 2)

---

## Troubleshooting

### Container won't start

```bash
# Check logs
docker compose logs app
docker compose logs worker

# Common fix: Redis not ready yet
docker compose down && docker compose up -d
```

### "ffprobe failed" error

FFmpeg is included in the Docker image. If running locally:
```bash
sudo apt install -y ffmpeg
```

### Upload timeout

For large videos over slow connections, increase the timeout:
```bash
# In docker-compose.yml, add to app environment:
- UVICORN_TIMEOUT_KEEP_ALIVE=120
```

### ROCm GPU not detected

```bash
# Check ROCm installation
rocm-smi

# Check device permissions
ls -la /dev/kfd /dev/dri/render*

# Ensure user is in video+render groups
groups $USER

# May need a reboot after ROCm installation
sudo reboot
```

### Worker not processing jobs

```bash
# Check worker is running
docker compose ps

# Check worker logs
docker compose logs worker -f

# Restart worker
docker compose restart worker
```

### Memory issues (OOM)

With 8GB VRAM, large models may run out of memory:
```bash
# Reduce worker concurrency in docker-compose.yml:
command: celery -A worker.celery_app worker --loglevel=info --concurrency=1
```

---

## Development (Running Locally)

For local development without Docker:

```bash
# Install dependencies
pip install -r requirements.txt

# Start Redis (required)
redis-server &

# Start the API server
uvicorn main:app --reload --host 0.0.0.0 --port 8000

# Start the Celery worker (separate terminal)
celery -A worker.celery_app worker --loglevel=info
```

---

## Processing Pipeline Details

### Audio Analysis
- **Onset detection**: Spectral flux algorithm identifies transients (impacts, beats)
- **Band energy**: Sub-bass (20-80Hz), bass (80-250Hz), mid (250-2kHz), high (2k+Hz)
- **Event classification**: Frequency profile analysis to identify explosions, gunshots, engines, footsteps, rain, speech
- **Amplitude envelope**: Overall volume curve over time

### Video Analysis
- **Optical flow**: Farneback dense flow between consecutive frames
- **Camera shake**: Global motion energy from flow field uniformity
- **Scene detection**: Histogram comparison between adjacent frames
- **Motion intensity**: Average flow magnitude per frame

### Haptic Generation
- **Transient events**: Sharp impacts mapped to VibrationEffect primitives (CLICK, THUD)
- **Continuous events**: Sustained vibrations with amplitude curves
- **Texture events**: Rapid sequences for rain, gravel, wind
- **Silence enforcement**: ≥30% of timeline has no haptics (prevents constant buzzing)
- **Post-processing**: Event merging, peak normalization, intensity smoothing
