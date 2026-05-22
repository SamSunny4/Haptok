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
            device_name = torch.cuda.get_device_name(0)
            # ROCm exposes AMD GPUs through the CUDA API in PyTorch
            if "gfx" in device_name.lower() or "amd" in device_name.lower():
                logger.info("Detected AMD ROCm GPU: %s", device_name)
            else:
                logger.info("Detected CUDA GPU: %s", device_name)
            return "cuda"
        else:
            logger.info("No GPU detected, falling back to CPU")
            return "cpu"
    except ImportError:
        logger.warning("PyTorch not installed, defaulting to CPU")
        return "cpu"
    except Exception as e:
        logger.warning("Device detection failed (%s), defaulting to CPU", e)
        return "cpu"


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
UPLOAD_DIR: str = os.getenv("HAPTOK_UPLOAD_DIR", "/tmp/haptok/uploads")
MODELS_DIR: str = os.getenv("HAPTOK_MODELS_DIR", "/opt/haptok/models")

# ---------------------------------------------------------------------------
# Video constraints
# ---------------------------------------------------------------------------
MAX_VIDEO_DURATION: int = int(os.getenv("HAPTOK_MAX_VIDEO_DURATION", "240"))  # seconds
MAX_FILE_SIZE: int = int(os.getenv("HAPTOK_MAX_FILE_SIZE", str(500 * 1024 * 1024)))  # bytes

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
