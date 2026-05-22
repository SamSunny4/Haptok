"""
Haptok FastAPI Application

REST + WebSocket API for uploading videos and receiving haptic timelines.
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
from fastapi.responses import JSONResponse

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
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host=config.HOST, port=config.PORT, reload=True)
