"""
Haptok FastAPI Application

REST + WebSocket API for uploading videos and receiving haptic timelines.
Includes a simple web dashboard at /dashboard.
"""

from __future__ import annotations

import json
import logging
import subprocess
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncGenerator

import aiofiles
import redis.asyncio as aioredis
from fastapi import (
    FastAPI,
    File,
    Form,
    HTTPException,
    UploadFile,
    WebSocket,
    WebSocketDisconnect,
    status,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse

import config
from schemas import (
    DeviceProfile,
    HapticTimeline,
    JobStatus,
    JobStatusEnum,
    UploadResponse,
)

logger = logging.getLogger("haptok.api")
logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(name)s | %(levelname)s | %(message)s")

# ---------------------------------------------------------------------------
# Redis connection (async)
# ---------------------------------------------------------------------------
redis_client: aioredis.Redis | None = None


async def get_redis() -> aioredis.Redis:
    """Return the async Redis client, raising if not connected."""
    if redis_client is None:
        raise HTTPException(status_code=503, detail="Redis not available")
    return redis_client


# ---------------------------------------------------------------------------
# Lifespan
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Startup / shutdown lifecycle handler."""
    global redis_client
    logger.info("Starting Haptok backend – device=%s", config.DEVICE)
    redis_client = aioredis.from_url(config.REDIS_URL, decode_responses=True)
    try:
        await redis_client.ping()
        logger.info("Connected to Redis at %s", config.REDIS_URL)
    except Exception as exc:
        logger.warning("Redis not reachable (%s) — status tracking will fail", exc)
    yield
    if redis_client:
        await redis_client.aclose()
        logger.info("Redis connection closed")


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------

app = FastAPI(
    title="Haptok API",
    description="Video-to-haptics processing pipeline",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _probe_duration(file_path: str) -> float:
    """Use ffprobe to get video duration in seconds."""
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file_path,
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        return float(result.stdout.strip())
    except Exception as exc:
        logger.error("ffprobe failed for %s: %s", file_path, exc)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Could not determine video duration: {exc}",
        )


async def _update_job_status(
    job_id: str,
    job_status: JobStatusEnum,
    progress: float = 0.0,
    message: str = "",
    error: str | None = None,
    result_ready: bool = False,
) -> None:
    """Store job status in Redis and publish update on the job channel."""
    r = await get_redis()
    payload = JobStatus(
        job_id=job_id,
        status=job_status,
        progress=progress,
        message=message,
        error=error,
        result_ready=result_ready,
    )
    data = payload.model_dump(mode="json", by_alias=True)
    await r.set(f"job:{job_id}:status", json.dumps(data), ex=86400)
    await r.publish(f"job:{job_id}", json.dumps(data))


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/health", tags=["system"])
async def health_check() -> dict:
    """Health check endpoint."""
    redis_ok = False
    if redis_client:
        try:
            await redis_client.ping()
            redis_ok = True
        except Exception:
            pass
    return {
        "status": "healthy",
        "device": config.DEVICE,
        "redis": "connected" if redis_ok else "disconnected",
    }


@app.get("/api/v1/system/stats", tags=["system"])
async def system_stats() -> dict:
    """System stats: GPU usage, active jobs, queue depth."""
    gpu = config.get_gpu_stats()

    # Count active jobs from Redis
    active_jobs = []
    r = await get_redis()
    keys = []
    async for key in r.scan_iter("job:*:status"):
        keys.append(key)
    for key in keys[-20:]:  # Last 20 jobs
        raw = await r.get(key)
        if raw:
            try:
                job = json.loads(raw)
                active_jobs.append({
                    "job_id": job.get("job_id", ""),
                    "status": job.get("status", ""),
                    "progress": job.get("progress", 0),
                    "message": job.get("message", ""),
                    "stage_detail": job.get("stage_detail", ""),
                    "eta_seconds": job.get("eta_seconds"),
                })
            except Exception:
                pass

    return {
        "gpu": gpu,
        "device": config.DEVICE,
        "max_file_size_mb": config.MAX_FILE_SIZE // (1024 * 1024),
        "max_duration_s": config.MAX_VIDEO_DURATION,
        "jobs": sorted(active_jobs, key=lambda j: j.get("status", ""), reverse=True),
    }


@app.post(
    "/api/v1/videos/upload",
    response_model=UploadResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["videos"],
)
async def upload_video(
    file: UploadFile = File(..., description="Video file (mp4, mov, mkv, webm)"),
    device_profile: str = Form(
        default='{}',
        description="JSON string with device haptic capabilities",
    ),
) -> UploadResponse:
    """
    Upload a video file and start haptic processing.

    The video is validated for size and duration, then a Celery background task
    is launched. The response contains a ``job_id`` to track progress.
    """
    # ── validate extension ──
    allowed_ext = {".mp4", ".mov", ".mkv", ".webm", ".avi"}
    suffix = Path(file.filename or "video.mp4").suffix.lower()
    if suffix not in allowed_ext:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=f"Unsupported file type '{suffix}'. Allowed: {allowed_ext}",
        )

    # ── parse device profile ──
    try:
        profile_dict = json.loads(device_profile)
        profile = DeviceProfile(**profile_dict) if profile_dict else DeviceProfile()
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid device_profile JSON: {exc}",
        )

    # ── save file ──
    job_id = str(uuid.uuid4())
    upload_dir = Path(config.UPLOAD_DIR) / job_id
    upload_dir.mkdir(parents=True, exist_ok=True)
    video_path = upload_dir / f"input{suffix}"

    total_bytes = 0
    async with aiofiles.open(video_path, "wb") as out:
        while chunk := await file.read(1024 * 1024):  # 1 MB chunks
            total_bytes += len(chunk)
            if total_bytes > config.MAX_FILE_SIZE:
                # Cleanup partial file
                video_path.unlink(missing_ok=True)
                raise HTTPException(
                    status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                    detail=f"File exceeds maximum size of {config.MAX_FILE_SIZE / 1024 / 1024:.0f} MB",
                )
            await out.write(chunk)

    # ── validate duration ──
    duration = _probe_duration(str(video_path))
    if duration > config.MAX_VIDEO_DURATION:
        video_path.unlink(missing_ok=True)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Video duration {duration:.1f}s exceeds maximum of {config.MAX_VIDEO_DURATION}s",
        )

    # ── store initial status ──
    await _update_job_status(job_id, JobStatusEnum.QUEUED, message="Job queued for processing")

    # ── enqueue Celery task ──
    from worker import process_video  # deferred import to avoid circular dep

    process_video.delay(job_id, str(video_path), profile.model_dump(mode="json", by_alias=True))
    logger.info("Job %s queued – video=%s size=%d duration=%.1fs", job_id, video_path, total_bytes, duration)

    return UploadResponse(job_id=job_id, status=JobStatusEnum.QUEUED)


@app.get("/api/v1/jobs/{job_id}/status", response_model=JobStatus, tags=["jobs"])
async def get_job_status(job_id: str) -> JobStatus:
    """Get the current processing status for a job."""
    r = await get_redis()
    raw = await r.get(f"job:{job_id}:status")
    if not raw:
        raise HTTPException(status_code=404, detail=f"Job '{job_id}' not found")
    return JobStatus(**json.loads(raw))


@app.get("/api/v1/jobs/{job_id}/haptics", tags=["jobs"])
async def get_haptic_timeline(job_id: str) -> JSONResponse:
    """
    Retrieve the generated haptic timeline JSON for a completed job.
    """
    r = await get_redis()
    raw_status = await r.get(f"job:{job_id}:status")
    if not raw_status:
        raise HTTPException(status_code=404, detail=f"Job '{job_id}' not found")

    job = JobStatus(**json.loads(raw_status))
    if job.status == JobStatusEnum.FAILED:
        raise HTTPException(status_code=422, detail=f"Job failed: {job.error}")
    if job.status != JobStatusEnum.COMPLETE:
        raise HTTPException(status_code=409, detail=f"Job not complete yet. Status: {job.status.value}")

    raw_result = await r.get(f"job:{job_id}:result")
    if not raw_result:
        raise HTTPException(status_code=404, detail="Haptic result not found")

    return JSONResponse(content=json.loads(raw_result))


@app.get("/api/v1/jobs/{job_id}/haptics/download", tags=["jobs"])
async def download_haptic_timeline(job_id: str) -> JSONResponse:
    """Download haptic timeline as a file."""
    r = await get_redis()
    raw_result = await r.get(f"job:{job_id}:result")
    if not raw_result:
        raise HTTPException(status_code=404, detail="Haptic result not found")

    return JSONResponse(
        content=json.loads(raw_result),
        headers={"Content-Disposition": f'attachment; filename="haptic_{job_id[:8]}.json"'},
    )


@app.websocket("/ws/jobs/{job_id}")
async def websocket_job_updates(websocket: WebSocket, job_id: str) -> None:
    """
    WebSocket endpoint that streams real-time status updates for a job.

    The client connects and receives JSON status messages as the pipeline
    progresses. The connection closes when the job completes or fails.
    """
    await websocket.accept()

    r = await get_redis()

    # Send current status immediately
    raw = await r.get(f"job:{job_id}:status")
    if raw:
        await websocket.send_text(raw)
        current = json.loads(raw)
        if current.get("status") in (JobStatusEnum.COMPLETE.value, JobStatusEnum.FAILED.value):
            await websocket.close()
            return

    # Subscribe to updates
    pubsub = r.pubsub()
    await pubsub.subscribe(f"job:{job_id}")

    try:
        async for message in pubsub.listen():
            if message["type"] == "message":
                data = message["data"]
                await websocket.send_text(data if isinstance(data, str) else data.decode())
                parsed = json.loads(data)
                if parsed.get("status") in (JobStatusEnum.COMPLETE.value, JobStatusEnum.FAILED.value):
                    break
    except WebSocketDisconnect:
        logger.info("WebSocket disconnected for job %s", job_id)
    finally:
        await pubsub.unsubscribe(f"job:{job_id}")
        await pubsub.aclose()


# ---------------------------------------------------------------------------
# Dashboard
# ---------------------------------------------------------------------------

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Haptok Dashboard</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: 'Segoe UI', system-ui, sans-serif; background: #0a0a1a; color: #e0e0e0; padding: 20px; }
  h1 { color: #7c4dff; margin-bottom: 20px; font-size: 28px; }
  h2 { color: #00bcd4; margin: 20px 0 10px; font-size: 18px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 16px; margin-bottom: 20px; }
  .card {
    background: linear-gradient(135deg, rgba(124,77,255,0.1), rgba(0,188,212,0.05));
    border: 1px solid rgba(124,77,255,0.3); border-radius: 12px; padding: 16px;
  }
  .card-title { font-size: 12px; text-transform: uppercase; color: #888; margin-bottom: 8px; letter-spacing: 1px; }
  .card-value { font-size: 32px; font-weight: 700; color: #fff; }
  .card-sub { font-size: 13px; color: #aaa; margin-top: 4px; }
  .gauge { width: 100%; height: 8px; background: #1a1a2e; border-radius: 4px; overflow: hidden; margin-top: 8px; }
  .gauge-fill { height: 100%; border-radius: 4px; transition: width 0.5s ease; }
  .gauge-gpu { background: linear-gradient(90deg, #7c4dff, #00bcd4); }
  .gauge-mem { background: linear-gradient(90deg, #ff6b6b, #ffa726); }
  table { width: 100%; border-collapse: collapse; margin-top: 10px; }
  th { text-align: left; padding: 10px 12px; background: rgba(124,77,255,0.15); color: #7c4dff; font-size: 12px; text-transform: uppercase; letter-spacing: 1px; }
  td { padding: 10px 12px; border-bottom: 1px solid rgba(255,255,255,0.05); font-size: 13px; }
  tr:hover td { background: rgba(124,77,255,0.05); }
  .status-badge { padding: 3px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; text-transform: uppercase; }
  .status-complete { background: #1b5e20; color: #66bb6a; }
  .status-processing { background: #1a237e; color: #7c4dff; }
  .status-queued { background: #33333a; color: #aaa; }
  .status-failed { background: #b71c1c; color: #ef5350; }
  .progress-bar { width: 100px; height: 6px; background: #1a1a2e; border-radius: 3px; display: inline-block; vertical-align: middle; }
  .progress-fill { height: 100%; background: #00bcd4; border-radius: 3px; transition: width 0.3s; }
  .refresh-note { font-size: 11px; color: #555; margin-top: 12px; }
  .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
  .live-dot { width: 8px; height: 8px; border-radius: 50%; background: #66bb6a; display: inline-block; animation: pulse 2s infinite; margin-right: 6px; }
  @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.3; } }
</style>
</head>
<body>
<div class="header">
  <h1>⚡ Haptok Dashboard</h1>
  <span><span class="live-dot"></span>Live — auto-refreshes every 2s</span>
</div>

<div class="grid">
  <div class="card">
    <div class="card-title">GPU Usage</div>
    <div class="card-value" id="gpu-usage">—</div>
    <div class="gauge"><div class="gauge-fill gauge-gpu" id="gpu-gauge" style="width:0%"></div></div>
    <div class="card-sub" id="gpu-temp"></div>
  </div>
  <div class="card">
    <div class="card-title">VRAM</div>
    <div class="card-value" id="vram-usage">—</div>
    <div class="gauge"><div class="gauge-fill gauge-mem" id="vram-gauge" style="width:0%"></div></div>
    <div class="card-sub" id="vram-detail"></div>
  </div>
  <div class="card">
    <div class="card-title">Device</div>
    <div class="card-value" id="device-type" style="font-size:22px;">—</div>
    <div class="card-sub">Max upload: <span id="max-size">—</span> MB | Max duration: <span id="max-dur">—</span>s</div>
  </div>
  <div class="card">
    <div class="card-title">Active Jobs</div>
    <div class="card-value" id="job-count">0</div>
    <div class="card-sub" id="job-summary"></div>
  </div>
</div>

<h2>Recent Jobs</h2>
<table>
  <thead><tr><th>Job ID</th><th>Status</th><th>Progress</th><th>Message</th><th>ETA</th></tr></thead>
  <tbody id="jobs-table"><tr><td colspan="5">Loading...</td></tr></tbody>
</table>

<div class="refresh-note">Dashboard polls /api/v1/system/stats every 2 seconds</div>

<script>
async function refresh() {
  try {
    const resp = await fetch('/api/v1/system/stats');
    const data = await resp.json();

    // GPU
    const gpuPct = data.gpu?.usage_pct;
    document.getElementById('gpu-usage').textContent = gpuPct != null ? gpuPct.toFixed(0) + '%' : 'N/A';
    document.getElementById('gpu-gauge').style.width = (gpuPct || 0) + '%';
    const temp = data.gpu?.temperature_c;
    document.getElementById('gpu-temp').textContent = temp != null ? temp + '°C' : '';

    // VRAM
    const vramUsed = data.gpu?.memory_used_mb;
    const vramTotal = data.gpu?.memory_total_mb;
    if (vramUsed != null && vramTotal != null) {
      document.getElementById('vram-usage').textContent = (vramUsed/1024).toFixed(1) + ' GB';
      document.getElementById('vram-gauge').style.width = ((vramUsed/vramTotal)*100).toFixed(0) + '%';
      document.getElementById('vram-detail').textContent = vramUsed + ' / ' + vramTotal + ' MB';
    } else {
      document.getElementById('vram-usage').textContent = 'N/A';
    }

    // Device
    document.getElementById('device-type').textContent = data.device || 'cpu';
    document.getElementById('max-size').textContent = data.max_file_size_mb || '—';
    document.getElementById('max-dur').textContent = data.max_duration_s || '—';

    // Jobs
    const jobs = data.jobs || [];
    const active = jobs.filter(j => j.status === 'processing' || j.status === 'queued');
    document.getElementById('job-count').textContent = active.length;
    document.getElementById('job-summary').textContent = jobs.length + ' total tracked';

    const tbody = document.getElementById('jobs-table');
    if (jobs.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" style="color:#555">No jobs yet</td></tr>';
    } else {
      tbody.innerHTML = jobs.map(j => {
        const statusClass = 'status-' + (j.status || 'queued');
        const pct = ((j.progress || 0) * 100).toFixed(0);
        const eta = j.eta_seconds != null ? j.eta_seconds.toFixed(0) + 's' : '—';
        return '<tr>' +
          '<td style="font-family:monospace;font-size:12px">' + (j.job_id || '').substring(0, 8) + '</td>' +
          '<td><span class="status-badge ' + statusClass + '">' + (j.status || '') + '</span></td>' +
          '<td><div class="progress-bar"><div class="progress-fill" style="width:' + pct + '%"></div></div> ' + pct + '%</td>' +
          '<td>' + (j.message || j.stage_detail || '') + '</td>' +
          '<td>' + eta + '</td></tr>';
      }).join('');
    }
  } catch(e) {
    console.error('Dashboard refresh failed:', e);
  }
}
refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>"""


@app.get("/dashboard", response_class=HTMLResponse, tags=["system"])
async def dashboard():
    """Simple web dashboard for monitoring Haptok backend."""
    return HTMLResponse(content=DASHBOARD_HTML)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=config.HOST, port=config.PORT, reload=True)
