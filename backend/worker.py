"""
Celery worker for the Haptok video-to-haptics processing pipeline.

Each task runs through: extract → analyze audio → analyze video → fuse → generate.
"""

from __future__ import annotations

import json
import logging
import shutil
import subprocess
import time
import traceback
from dataclasses import asdict
from pathlib import Path

import redis

from celery import Celery

import config
from schemas import JobStatusEnum

logger = logging.getLogger("haptok.worker")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(name)s | %(levelname)s | %(message)s",
)

# ---------------------------------------------------------------------------
# Celery application
# ---------------------------------------------------------------------------

celery_app = Celery(
    "haptok",
    broker=config.CELERY_BROKER_URL,
    backend=config.CELERY_RESULT_BACKEND,
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    task_track_started=True,
    worker_concurrency=2,  # limit for RX 6600 memory
)

# Synchronous Redis for status updates from within the worker
_redis: redis.Redis | None = None


def _get_redis() -> redis.Redis:
    global _redis
    if _redis is None:
        _redis = redis.from_url(config.REDIS_URL, decode_responses=True)
    return _redis


def _update_status(
    job_id: str,
    status: JobStatusEnum,
    progress: float = 0.0,
    message: str = "",
    error: str | None = None,
    result_ready: bool = False,
    stage_detail: str | None = None,
    eta_seconds: float | None = None,
    gpu_usage_pct: float | None = None,
) -> None:
    """Push a status update into Redis and publish on the job channel."""
    r = _get_redis()

    # Collect GPU stats if available
    if gpu_usage_pct is None:
        gpu_stats = config.get_gpu_stats()
        gpu_usage_pct = gpu_stats.get("usage_pct")

    payload = {
        "job_id": job_id,
        "status": status.value,
        "progress": progress,
        "message": message,
        "error": error,
        "result_ready": result_ready,
        "stage_detail": stage_detail,
        "eta_seconds": eta_seconds,
        "gpu_usage_pct": gpu_usage_pct,
    }
    r.set(f"job:{job_id}:status", json.dumps(payload), ex=86400)
    r.publish(f"job:{job_id}", json.dumps(payload))


def _estimate_eta(start_time: float, progress: float) -> float | None:
    """Estimate remaining seconds based on elapsed time and progress."""
    if progress <= 0.01:
        return None
    elapsed = time.time() - start_time
    estimated_total = elapsed / progress
    return max(0, estimated_total - elapsed)


# ---------------------------------------------------------------------------
# FFmpeg extraction helpers
# ---------------------------------------------------------------------------

def _extract_audio(video_path: str, output_dir: Path) -> Path:
    """Extract audio to WAV using FFmpeg."""
    audio_path = output_dir / "audio.wav"
    cmd = [
        "ffmpeg", "-y", "-i", video_path,
        "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1",
        str(audio_path),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(f"FFmpeg audio extraction failed: {result.stderr[:500]}")
    return audio_path


def _extract_keyframes(video_path: str, output_dir: Path) -> Path:
    """Extract keyframes at 1 fps + scene changes."""
    frames_dir = output_dir / "frames"
    frames_dir.mkdir(exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-i", video_path,
        "-vf", "fps=1",
        "-q:v", "2",
        str(frames_dir / "frame_%04d.jpg"),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    if result.returncode != 0:
        raise RuntimeError(f"FFmpeg frame extraction failed: {result.stderr[:500]}")
    return frames_dir


# ---------------------------------------------------------------------------
# FusedFeatures → dict bridge for haptic_generator
# ---------------------------------------------------------------------------

def _fused_to_generator_dict(fused, audio_features, video_features):
    """
    Convert FusedFeatures dataclass into the dict format expected by
    haptic_generator.generate().

    The generator expects:
      - events: list of {time, type, confidence, source}
      - onset_events: list of {time, strength}
      - motion_events: list of {time, intensity}
      - scene_changes: list of float timestamps
      - bass_energy_curve: list of (time, value) tuples
      - camera_shake_curve: list of (time, value) tuples
    """
    # Classified events (explosion, speech, etc.)
    events = []
    onset_events = []
    for e in fused.events:
        if e.event_type == "onset":
            onset_events.append({
                "time": e.time,
                "strength": e.intensity,
            })
        else:
            events.append({
                "time": e.time,
                "type": e.event_type,
                "confidence": e.intensity,  # fused intensity ≈ confidence
                "source": e.source,
            })

    # Motion events from video
    motion_events = [
        {"time": t, "intensity": v}
        for t, v in video_features.motion_intensity
        if v > 0.1
    ]

    # Scene changes
    scene_changes = video_features.scene_changes

    # Curves
    bass_energy_curve = []
    camera_shake_curve = []
    for curve in fused.curves:
        if curve.name == "bass":
            bass_energy_curve = curve.points
        elif curve.name == "sub_bass" and not bass_energy_curve:
            bass_energy_curve = curve.points
        elif curve.name == "camera_shake":
            camera_shake_curve = curve.points

    return {
        "events": events,
        "onset_events": onset_events,
        "motion_events": motion_events,
        "scene_changes": scene_changes,
        "bass_energy_curve": bass_energy_curve,
        "camera_shake_curve": camera_shake_curve,
    }


# ---------------------------------------------------------------------------
# Main processing task
# ---------------------------------------------------------------------------

@celery_app.task(bind=True, name="worker.process_video", max_retries=1)
def process_video(self, job_id: str, video_path: str, device_profile: dict) -> dict:
    """
    End-to-end video-to-haptics processing pipeline.

    Stages: extracting → analyzing_audio → analyzing_video →
            fusing → generating → complete
    """
    work_dir = Path(video_path).parent
    start_time = time.time()

    try:
        # ── Stage 1: Extract ──────────────────────────────────────────
        _update_status(
            job_id, JobStatusEnum.PROCESSING, 0.05,
            "Extracting audio and keyframes...",
            stage_detail="Running FFmpeg to extract audio track",
            eta_seconds=_estimate_eta(start_time, 0.05),
        )
        logger.info("[%s] Extracting audio...", job_id)
        audio_path = _extract_audio(video_path, work_dir)

        _update_status(
            job_id, JobStatusEnum.PROCESSING, 0.10,
            "Extracting keyframes...",
            stage_detail="Extracting video frames at 1fps for analysis",
            eta_seconds=_estimate_eta(start_time, 0.10),
        )
        logger.info("[%s] Extracting keyframes...", job_id)
        frames_dir = _extract_keyframes(video_path, work_dir)

        # ── Stage 2: Audio analysis ───────────────────────────────────
        _update_status(
            job_id, JobStatusEnum.PROCESSING, 0.20,
            "Analyzing audio...",
            stage_detail="Detecting onsets, classifying events, extracting frequency bands",
            eta_seconds=_estimate_eta(start_time, 0.20),
        )
        logger.info("[%s] Analyzing audio...", job_id)
        from audio_analyzer import analyze as analyze_audio
        audio_features = analyze_audio(str(audio_path))

        # ── Stage 3: Video analysis ───────────────────────────────────
        _update_status(
            job_id, JobStatusEnum.PROCESSING, 0.40,
            "Analyzing video...",
            stage_detail="Computing optical flow, detecting camera shake and scene changes",
            eta_seconds=_estimate_eta(start_time, 0.40),
        )
        logger.info("[%s] Analyzing video...", job_id)
        from video_analyzer import analyze as analyze_video
        video_features = analyze_video(video_path, str(frames_dir))

        # ── Stage 4: Multimodal fusion ────────────────────────────────
        _update_status(
            job_id, JobStatusEnum.PROCESSING, 0.60,
            "Fusing audio and video features...",
            stage_detail="Merging audio events with video motion data",
            eta_seconds=_estimate_eta(start_time, 0.60),
        )
        logger.info("[%s] Fusing features...", job_id)
        from multimodal_fusion import fuse
        fused = fuse(audio_features, video_features)

        # ── Stage 5: Haptic generation ────────────────────────────────
        _update_status(
            job_id, JobStatusEnum.PROCESSING, 0.75,
            "Generating haptic timeline...",
            stage_detail="Converting fused events into haptic waveform with post-processing",
            eta_seconds=_estimate_eta(start_time, 0.75),
        )
        logger.info("[%s] Generating haptics...", job_id)
        from haptic_generator import generate

        # Bridge: convert FusedFeatures dataclass → dict for generator
        duration = fused.duration or audio_features.duration or video_features.duration or 60.0
        generator_input = _fused_to_generator_dict(fused, audio_features, video_features)
        timeline = generate(generator_input, device_profile, duration)

        # ── Store result ──────────────────────────────────────────────
        r = _get_redis()
        r.set(f"job:{job_id}:result", json.dumps(timeline), ex=86400)

        elapsed = time.time() - start_time
        _update_status(
            job_id, JobStatusEnum.COMPLETE, 1.0,
            f"Complete in {elapsed:.1f}s",
            result_ready=True,
            stage_detail=f"Generated {sum(len(t.get('events', [])) for t in timeline.get('tracks', []))} haptic events",
        )
        logger.info("[%s] Pipeline complete in %.1fs", job_id, elapsed)

        return {"job_id": job_id, "status": "complete", "elapsed_seconds": elapsed}

    except Exception as exc:
        logger.exception("[%s] Pipeline failed", job_id)
        tb = traceback.format_exc()
        _update_status(
            job_id, JobStatusEnum.FAILED,
            error=str(exc),
            message="Processing failed",
            stage_detail=tb[-500:],  # Last 500 chars of traceback
        )
        raise

    finally:
        # Clean up frames directory (keep audio for debugging)
        frames_dir_path = work_dir / "frames"
        if frames_dir_path.exists():
            shutil.rmtree(frames_dir_path, ignore_errors=True)
