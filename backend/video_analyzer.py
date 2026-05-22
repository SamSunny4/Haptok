"""
Haptok Video Analyzer

Extracts visual features for haptic generation:
- Optical flow (Farneback via OpenCV – CPU, no GPU required)
- Camera shake detection from global motion vectors
- Scene change detection via histogram comparison

Phase 2 TODO: Integrate SlowFast for action recognition from
  {MODELS_DIR}/slowfast_r50.pth
"""

from __future__ import annotations

import logging
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Tuple

import cv2
import numpy as np
import numpy.typing as npt

import config

logger = logging.getLogger("haptok.video")


# ──────────────────────────────────────────────────────────────────────────────
# Output data structure
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class VideoFeatures:
    """Container for all extracted video features."""
    motion_intensity: list[Tuple[float, float]] = field(default_factory=list)
    camera_shake: list[Tuple[float, float]] = field(default_factory=list)
    scene_changes: list[float] = field(default_factory=list)
    actions: list[Tuple[float, str, float]] = field(default_factory=list)  # (time, action, confidence)
    duration: float = 0.0


# ──────────────────────────────────────────────────────────────────────────────
# Frame extraction
# ──────────────────────────────────────────────────────────────────────────────

def extract_frames(
    video_path: str,
    output_dir: str | None = None,
    fps: int = config.VIDEO_ANALYSIS_FPS,
) -> list[str]:
    """
    Extract frames from a video at the specified FPS using FFmpeg.

    Args:
        video_path: Path to the source video.
        output_dir: Directory to write frame images. Created if missing.
        fps: Frames per second to extract.

    Returns:
        Sorted list of extracted frame file paths.
    """
    if output_dir is None:
        output_dir = str(Path(video_path).parent / "frames")
    Path(output_dir).mkdir(parents=True, exist_ok=True)

    output_pattern = str(Path(output_dir) / "frame_%06d.jpg")

    cmd = [
        "ffmpeg", "-y", "-i", video_path,
        "-vf", f"fps={fps}",
        "-q:v", "2",  # high quality JPEG
        output_pattern,
    ]
    logger.info("Extracting frames: %s", " ".join(cmd))
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        logger.error("FFmpeg frame extraction failed: %s", result.stderr)
        raise RuntimeError(f"FFmpeg frame extraction failed: {result.stderr[:500]}")

    frames = sorted(str(p) for p in Path(output_dir).glob("frame_*.jpg"))
    logger.info("Extracted %d frames at %d fps", len(frames), fps)
    return frames


# ──────────────────────────────────────────────────────────────────────────────
# Optical flow
# ──────────────────────────────────────────────────────────────────────────────

def _load_gray(path: str) -> npt.NDArray[np.uint8]:
    """Load an image as grayscale, resized to 480p width for speed."""
    img = cv2.imread(path)
    if img is None:
        raise FileNotFoundError(f"Cannot read frame: {path}")
    h, w = img.shape[:2]
    target_w = 480
    if w > target_w:
        scale = target_w / w
        img = cv2.resize(img, (target_w, int(h * scale)), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)


def compute_optical_flow(
    frame_paths: list[str],
    fps: int = config.VIDEO_ANALYSIS_FPS,
) -> list[Tuple[float, float]]:
    """
    Compute dense optical flow between consecutive frames using Farneback.

    Returns:
        List of (time_seconds, mean_flow_magnitude) tuples.
    """
    if len(frame_paths) < 2:
        return []

    logger.info("Computing optical flow for %d frames", len(frame_paths))
    prev_gray = _load_gray(frame_paths[0])
    flow_magnitudes: list[Tuple[float, float]] = []

    for i in range(1, len(frame_paths)):
        curr_gray = _load_gray(frame_paths[i])
        flow = cv2.calcOpticalFlowFarneback(
            prev_gray, curr_gray,
            None,  # type: ignore[arg-type]
            pyr_scale=0.5,
            levels=3,
            winsize=15,
            iterations=3,
            poly_n=5,
            poly_sigma=1.2,
            flags=0,
        )
        mag, _ = cv2.cartToPolar(flow[..., 0], flow[..., 1])
        mean_mag = float(np.mean(mag))
        t = i / fps
        flow_magnitudes.append((t, mean_mag))
        prev_gray = curr_gray

    # Normalise magnitudes to 0-1
    if flow_magnitudes:
        max_mag = max(m for _, m in flow_magnitudes)
        if max_mag > 0:
            flow_magnitudes = [(t, m / max_mag) for t, m in flow_magnitudes]

    return flow_magnitudes


# ──────────────────────────────────────────────────────────────────────────────
# Camera shake detection
# ──────────────────────────────────────────────────────────────────────────────

def detect_camera_shake(
    frame_paths: list[str],
    fps: int = config.VIDEO_ANALYSIS_FPS,
) -> list[Tuple[float, float]]:
    """
    Detect camera shake by analysing the variance of the global motion vector.

    High variance in flow direction = camera shake.

    Returns:
        List of (time, shake_magnitude) tuples normalised to 0-1.
    """
    if len(frame_paths) < 2:
        return []

    logger.info("Detecting camera shake across %d frames", len(frame_paths))
    prev_gray = _load_gray(frame_paths[0])
    shake_values: list[Tuple[float, float]] = []

    for i in range(1, len(frame_paths)):
        curr_gray = _load_gray(frame_paths[i])
        flow = cv2.calcOpticalFlowFarneback(
            prev_gray, curr_gray,
            None,  # type: ignore[arg-type]
            pyr_scale=0.5,
            levels=3,
            winsize=15,
            iterations=3,
            poly_n=5,
            poly_sigma=1.2,
            flags=0,
        )
        # Global motion = mean flow vector
        mean_flow_x = float(np.mean(flow[..., 0]))
        mean_flow_y = float(np.mean(flow[..., 1]))

        # Per-pixel deviation from global motion → shake
        deviation_x = flow[..., 0] - mean_flow_x
        deviation_y = flow[..., 1] - mean_flow_y
        deviation_mag = np.sqrt(deviation_x ** 2 + deviation_y ** 2)
        shake = float(np.std(deviation_mag))

        t = i / fps
        shake_values.append((t, shake))
        prev_gray = curr_gray

    # Normalise to 0-1
    if shake_values:
        max_s = max(s for _, s in shake_values)
        if max_s > 0:
            shake_values = [(t, s / max_s) for t, s in shake_values]

    return shake_values


# ──────────────────────────────────────────────────────────────────────────────
# Scene change detection
# ──────────────────────────────────────────────────────────────────────────────

def detect_scene_changes(
    frame_paths: list[str],
    fps: int = config.VIDEO_ANALYSIS_FPS,
    threshold: float = config.SCENE_CHANGE_THRESHOLD,
) -> list[float]:
    """
    Detect scene changes by comparing colour histograms between consecutive frames.

    Uses chi-squared distance on HSV histograms.

    Returns:
        List of timestamps (seconds) where scene changes occur.
    """
    if len(frame_paths) < 2:
        return []

    logger.info("Detecting scene changes across %d frames", len(frame_paths))

    def _compute_hist(path: str) -> npt.NDArray[np.float32]:
        img = cv2.imread(path)
        if img is None:
            raise FileNotFoundError(f"Cannot read frame: {path}")
        hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
        hist = cv2.calcHist([hsv], [0, 1], None, [50, 60], [0, 180, 0, 256])
        cv2.normalize(hist, hist)
        return hist.flatten()

    prev_hist = _compute_hist(frame_paths[0])
    scene_times: list[float] = []

    for i in range(1, len(frame_paths)):
        curr_hist = _compute_hist(frame_paths[i])
        dist = cv2.compareHist(
            prev_hist.reshape(-1, 1).astype(np.float32),
            curr_hist.reshape(-1, 1).astype(np.float32),
            cv2.HISTCMP_CHISQR,
        )
        # Normalise chi-squared distance (heuristic threshold)
        normalised = min(1.0, dist / 50.0)
        if normalised > threshold:
            t = i / fps
            scene_times.append(t)
            logger.debug("Scene change at %.2fs (dist=%.4f)", t, normalised)
        prev_hist = curr_hist

    logger.info("Detected %d scene changes", len(scene_times))
    return scene_times


# ──────────────────────────────────────────────────────────────────────────────
# Placeholder: SlowFast action recognition (Phase 2)
# ──────────────────────────────────────────────────────────────────────────────

def classify_actions(
    frame_paths: list[str],
    fps: int = config.VIDEO_ANALYSIS_FPS,
) -> list[Tuple[float, str, float]]:
    """
    Placeholder for SlowFast action recognition.

    Phase 2 TODO: Load SlowFast R50 from {MODELS_DIR}/slowfast_r50.pth,
    run sliding window (32 frames slow, 8 frames fast) over extracted frames,
    return per-window action predictions.

    For now, returns an empty list — video analysis relies on optical flow
    and scene changes in Phase 1.
    """
    logger.info(
        "Action recognition skipped (Phase 1). "
        "Will load SlowFast from %s/slowfast_r50.pth in Phase 2.",
        config.MODELS_DIR,
    )
    return []


# ──────────────────────────────────────────────────────────────────────────────
# Main analysis entry point
# ──────────────────────────────────────────────────────────────────────────────

def analyze(video_path: str, keyframes_dir: str | None = None) -> VideoFeatures:
    """
    Run the complete video analysis pipeline.

    Args:
        video_path: Path to the source video file.
        keyframes_dir: Optional directory for extracted frames. If None,
                       frames are extracted into a sibling ``frames/`` directory.

    Returns:
        VideoFeatures with motion intensity, camera shake, scene changes, and actions.
    """
    logger.info("Starting video analysis: %s", video_path)

    # Determine video duration via ffprobe
    try:
        result = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video_path,
            ],
            capture_output=True,
            text=True,
            timeout=30,
        )
        duration = float(result.stdout.strip())
    except Exception:
        duration = 0.0
        logger.warning("Could not determine video duration via ffprobe")

    # Extract frames
    frame_dir = keyframes_dir or str(Path(video_path).parent / "frames")
    frame_paths = extract_frames(video_path, frame_dir)

    if not frame_paths:
        logger.warning("No frames extracted, returning empty features")
        return VideoFeatures(duration=duration)

    # Optical flow → motion intensity
    motion_intensity = compute_optical_flow(frame_paths)

    # Camera shake
    camera_shake = detect_camera_shake(frame_paths)

    # Scene changes
    scene_changes = detect_scene_changes(frame_paths)

    # Action recognition (Phase 2 placeholder)
    actions = classify_actions(frame_paths)

    features = VideoFeatures(
        motion_intensity=motion_intensity,
        camera_shake=camera_shake,
        scene_changes=scene_changes,
        actions=actions,
        duration=duration,
    )

    logger.info(
        "Video analysis complete: duration=%.2fs, flow_points=%d, shakes=%d, scene_changes=%d",
        duration,
        len(motion_intensity),
        len(camera_shake),
        len(scene_changes),
    )
    return features
