"""
Haptok Backend Configuration

Loads configuration from environment variables with sensible defaults.
Auto-detects available compute device (ROCm GPU, CUDA GPU, or CPU).
"""

import os
import logging
from pathlib import Path

logger = logging.getLogger(__name__)


def _detect_device() -> str:
    """Auto-detect the best available compute device.

    Priority: ROCm (AMD GPU) > CUDA (NVIDIA GPU) > CPU.
    """
    try:
        import torch

        if torch.cuda.is_available():
            try:
                # Test actual allocation to ensure ROCm/CUDA doesn't crash on this hardware
                _ = torch.tensor([1.0]).to("cuda")
                device_name = torch.cuda.get_device_name(0)
                # ROCm exposes AMD GPUs through the CUDA API in PyTorch
                if "gfx" in device_name.lower() or "amd" in device_name.lower():
                    logger.info("Detected and verified AMD ROCm GPU: %s", device_name)
                else:
                    logger.info("Detected and verified CUDA GPU: %s", device_name)
                return "cuda"
            except Exception as e:
                logger.warning("GPU detected but tensor allocation failed (%s). Falling back to CPU.", e)
                return "cpu"
        else:
            logger.info("No GPU detected, falling back to CPU")
            return "cpu"
    except ImportError:
        logger.warning("PyTorch not installed, defaulting to CPU")
        return "cpu"
    except Exception as e:
        logger.warning("Device detection failed (%s), defaulting to CPU", e)
        return "cpu"


def get_gpu_stats() -> dict:
    """
    Read GPU stats from the system.

    Supports AMD ROCm (sysfs) and NVIDIA (nvidia-smi).
    Returns a dict with usage_pct, memory_used_mb, memory_total_mb, temperature_c.
    All values may be None if not available.
    """
    stats = {
        "usage_pct": None,
        "memory_used_mb": None,
        "memory_total_mb": None,
        "temperature_c": None,
    }

    if not GPU_STATS_ENABLED:
        return stats

    # Try AMD ROCm via sysfs
    try:
        gpu_busy = Path("/sys/class/drm/card0/device/gpu_busy_percent")
        if gpu_busy.exists():
            stats["usage_pct"] = float(gpu_busy.read_text().strip())

        # Try to get VRAM info
        vram_used = Path("/sys/class/drm/card0/device/mem_info_vram_used")
        vram_total = Path("/sys/class/drm/card0/device/mem_info_vram_total")
        if vram_used.exists() and vram_total.exists():
            stats["memory_used_mb"] = int(vram_used.read_text().strip()) // (1024 * 1024)
            stats["memory_total_mb"] = int(vram_total.read_text().strip()) // (1024 * 1024)

        # Temperature
        temp_file = Path("/sys/class/drm/card0/device/hwmon")
        if temp_file.exists():
            for hwmon_dir in temp_file.iterdir():
                temp1 = hwmon_dir / "temp1_input"
                if temp1.exists():
                    stats["temperature_c"] = int(temp1.read_text().strip()) // 1000
                    break

        if stats["usage_pct"] is not None:
            return stats
    except Exception:
        pass

    # Try NVIDIA via nvidia-smi
    try:
        import subprocess
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            parts = result.stdout.strip().split(",")
            if len(parts) >= 4:
                stats["usage_pct"] = float(parts[0].strip())
                stats["memory_used_mb"] = float(parts[1].strip())
                stats["memory_total_mb"] = float(parts[2].strip())
                stats["temperature_c"] = float(parts[3].strip())
    except Exception:
        pass

    return stats


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
UPLOAD_DIR: str = os.getenv("HAPTOK_UPLOAD_DIR", "/tmp/haptok/uploads")
MODELS_DIR: str = os.getenv("HAPTOK_MODELS_DIR", "/opt/haptok/models")

# ---------------------------------------------------------------------------
# Video constraints
# ---------------------------------------------------------------------------
MAX_VIDEO_DURATION: int = int(os.getenv("HAPTOK_MAX_VIDEO_DURATION", "240"))  # seconds
MAX_FILE_SIZE: int = int(os.getenv("HAPTOK_MAX_FILE_SIZE", str(256 * 1024 * 1024)))  # 256 MB

# ---------------------------------------------------------------------------
# Redis / Celery
# ---------------------------------------------------------------------------
REDIS_URL: str = os.getenv("HAPTOK_REDIS_URL", "redis://redis:6379/0")
CELERY_BROKER_URL: str = os.getenv("HAPTOK_CELERY_BROKER_URL", "redis://redis:6379/0")
CELERY_RESULT_BACKEND: str = os.getenv("HAPTOK_CELERY_RESULT_BACKEND", "redis://redis:6379/1")

# ---------------------------------------------------------------------------
# Server
# ---------------------------------------------------------------------------
HOST: str = os.getenv("HAPTOK_HOST", "0.0.0.0")
PORT: int = int(os.getenv("HAPTOK_PORT", "8000"))

# ---------------------------------------------------------------------------
# Compute device
# ---------------------------------------------------------------------------
DEVICE: str = os.getenv("HAPTOK_DEVICE", "auto")
if DEVICE == "auto":
    DEVICE = _detect_device()

# ---------------------------------------------------------------------------
# GPU stats
# ---------------------------------------------------------------------------
GPU_STATS_ENABLED: bool = os.getenv("HAPTOK_GPU_STATS_ENABLED", "true").lower() in ("true", "1", "yes")

# ---------------------------------------------------------------------------
# Analysis parameters
# ---------------------------------------------------------------------------
AUDIO_SAMPLE_RATE: int = 16_000
MEL_N_MELS: int = 128
MEL_N_FFT: int = 1024
MEL_HOP_LENGTH: int = 512
ANALYSIS_WINDOW_SEC: float = 0.5  # seconds per analysis window

# Onset detection
ONSET_THRESHOLD: float = float(os.getenv("HAPTOK_ONSET_THRESHOLD", "0.6"))

# Video analysis
VIDEO_ANALYSIS_FPS: int = int(os.getenv("HAPTOK_VIDEO_ANALYSIS_FPS", "1"))
SCENE_CHANGE_THRESHOLD: float = float(os.getenv("HAPTOK_SCENE_CHANGE_THRESHOLD", "0.35"))

# Haptic generation
HAPTIC_MIN_EVENT_GAP_MS: float = 10.0  # merge events closer than this (ms)
HAPTIC_MAX_INTENSITY_JUMP: float = 0.3  # max jump per 50ms
HAPTIC_SILENCE_RATIO: float = 0.30  # at least 30% silence

# ---------------------------------------------------------------------------
# Ensure directories exist
# ---------------------------------------------------------------------------
Path(UPLOAD_DIR).mkdir(parents=True, exist_ok=True)
Path(MODELS_DIR).mkdir(parents=True, exist_ok=True)
