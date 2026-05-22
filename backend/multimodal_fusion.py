"""
Haptok Multimodal Fusion

Fuses audio and video features into a unified timeline of multimodal events
and continuous curves that drive haptic generation.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Tuple

import numpy as np

from audio_analyzer import AudioFeatures
from video_analyzer import VideoFeatures

logger = logging.getLogger("haptok.fusion")


# ──────────────────────────────────────────────────────────────────────────────
# Output data structures
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class FusedEvent:
    """A single multimodal event on the fused timeline."""
    time: float
    event_type: str  # e.g. "explosion", "impact", "scene_change", "onset"
    intensity: float  # 0-1
    sharpness: float  # 0-1
    source: str  # "audio", "video", "both"
    tags: list[str] = field(default_factory=list)


@dataclass
class ContinuousCurve:
    """A continuous parameter curve for ambient haptics."""
    name: str  # e.g. "sub_bass", "motion", "camera_shake"
    source: str  # "audio", "video", "both"
    points: list[Tuple[float, float]] = field(default_factory=list)  # (time, value 0-1)


@dataclass
class FusedFeatures:
    """Container for the fused multimodal output."""
    events: list[FusedEvent] = field(default_factory=list)
    curves: list[ContinuousCurve] = field(default_factory=list)
    duration: float = 0.0


# ──────────────────────────────────────────────────────────────────────────────
# Timestamp alignment
# ──────────────────────────────────────────────────────────────────────────────

def align_timestamps(
    audio: AudioFeatures,
    video: VideoFeatures,
) -> float:
    """
    Determine the common duration for the timeline.

    Both audio and video should already be aligned to the source video's
    timeline (they were extracted from the same file). We just pick the
    agreed-upon duration.
    """
    duration = max(audio.duration, video.duration)
    if duration <= 0:
        duration = max(
            (audio.onset_times[-1] if audio.onset_times else 0.0),
            (video.motion_intensity[-1][0] if video.motion_intensity else 0.0),
        )
    return duration


# ──────────────────────────────────────────────────────────────────────────────
# Fusion helpers
# ──────────────────────────────────────────────────────────────────────────────

def _find_nearby_motion(
    time: float,
    motion: list[Tuple[float, float]],
    tolerance: float = 1.0,
) -> float:
    """Find the motion intensity value closest to the given time."""
    if not motion:
        return 0.0
    best = min(motion, key=lambda m: abs(m[0] - time))
    if abs(best[0] - time) <= tolerance:
        return best[1]
    return 0.0


def _find_nearby_shake(
    time: float,
    shakes: list[Tuple[float, float]],
    tolerance: float = 1.0,
) -> float:
    """Find the camera shake value closest to the given time."""
    if not shakes:
        return 0.0
    best = min(shakes, key=lambda s: abs(s[0] - time))
    if abs(best[0] - time) <= tolerance:
        return best[1]
    return 0.0


def _event_sharpness(event_type: str) -> float:
    """Map event type to a default sharpness value."""
    sharp_map = {
        "explosion": 0.3,
        "gunshot": 0.9,
        "impact": 0.7,
        "footstep": 0.5,
        "engine": 0.2,
        "rain": 0.1,
        "wind": 0.15,
        "music": 0.4,
        "speech": 0.3,
        "silence": 0.0,
        "scene_change": 0.8,
        "onset": 0.6,
    }
    return sharp_map.get(event_type, 0.5)


# ──────────────────────────────────────────────────────────────────────────────
# Main fusion
# ──────────────────────────────────────────────────────────────────────────────

def fuse(audio: AudioFeatures, video: VideoFeatures) -> FusedFeatures:
    """
    Fuse audio and video features into a unified multimodal representation.

    Strategy:
    1. Audio classified events → fused events, boosted by video motion.
    2. Audio onsets (not already covered by classified events) → onset events.
    3. Scene changes → transient burst events.
    4. Camera shake → continuous curve.
    5. Band energies (sub_bass, bass) → continuous ambient curves.
    6. Motion intensity → continuous motion curve.
    """
    duration = align_timestamps(audio, video)
    logger.info("Fusing features – duration=%.2fs", duration)

    fused_events: list[FusedEvent] = []
    fused_curves: list[ContinuousCurve] = []

    # ── 1. Audio classified events ────────────────────────────────────────────
    event_times_used: set[float] = set()

    for t, evt_type, confidence in audio.events:
        if evt_type == "silence":
            continue

        # Cross-reference with video motion
        motion_val = _find_nearby_motion(t, video.motion_intensity)
        shake_val = _find_nearby_shake(t, video.camera_shake)

        # Boost intensity if video confirms high activity
        video_boost = 0.0
        source = "audio"
        if motion_val > 0.4 or shake_val > 0.4:
            video_boost = max(motion_val, shake_val) * 0.2
            source = "both"

        intensity = min(1.0, confidence * 0.8 + video_boost)
        sharpness = _event_sharpness(evt_type)

        fused_events.append(FusedEvent(
            time=t,
            event_type=evt_type,
            intensity=round(intensity, 3),
            sharpness=round(sharpness, 3),
            source=source,
            tags=[evt_type],
        ))
        event_times_used.add(round(t, 2))

    # ── 2. Audio onsets not already covered ───────────────────────────────────
    for i, onset_t in enumerate(audio.onset_times):
        rounded = round(onset_t, 2)
        # Skip if an event already exists near this onset
        if any(abs(rounded - et) < 0.15 for et in event_times_used):
            continue

        strength = audio.onset_strengths[i] if i < len(audio.onset_strengths) else 0.5
        if strength < 0.3:  # ignore weak onsets
            continue

        motion_val = _find_nearby_motion(onset_t, video.motion_intensity)
        source = "both" if motion_val > 0.3 else "audio"
        intensity = min(1.0, strength * 0.7 + motion_val * 0.3)

        fused_events.append(FusedEvent(
            time=onset_t,
            event_type="onset",
            intensity=round(intensity, 3),
            sharpness=0.6,
            source=source,
            tags=["onset"],
        ))

    # ── 3. Scene changes → transient burst ────────────────────────────────────
    for sc_t in video.scene_changes:
        # Check if an event already exists near this time
        if any(abs(sc_t - e.time) < 0.2 for e in fused_events):
            # Boost existing event instead
            for e in fused_events:
                if abs(sc_t - e.time) < 0.2:
                    e.intensity = min(1.0, e.intensity + 0.1)
                    if "scene_change" not in e.tags:
                        e.tags.append("scene_change")
                    e.source = "both"
                    break
        else:
            fused_events.append(FusedEvent(
                time=sc_t,
                event_type="scene_change",
                intensity=0.5,
                sharpness=0.8,
                source="video",
                tags=["scene_change"],
            ))

    # Sort events by time
    fused_events.sort(key=lambda e: e.time)

    # ── 4. Continuous curves ──────────────────────────────────────────────────

    # Sub-bass energy curve (for low-frequency ambient rumble)
    if "sub_bass" in audio.band_energies:
        fused_curves.append(ContinuousCurve(
            name="sub_bass",
            source="audio",
            points=audio.band_energies["sub_bass"],
        ))

    # Bass energy curve
    if "bass" in audio.band_energies:
        fused_curves.append(ContinuousCurve(
            name="bass",
            source="audio",
            points=audio.band_energies["bass"],
        ))

    # Camera shake curve
    if video.camera_shake:
        fused_curves.append(ContinuousCurve(
            name="camera_shake",
            source="video",
            points=video.camera_shake,
        ))

    # Motion intensity curve
    if video.motion_intensity:
        fused_curves.append(ContinuousCurve(
            name="motion",
            source="video",
            points=video.motion_intensity,
        ))

    # Combined ambient curve: merge sub-bass + motion intensity
    if "sub_bass" in audio.band_energies and video.motion_intensity:
        combined = _merge_curves(
            audio.band_energies["sub_bass"],
            video.motion_intensity,
            weight_a=0.6,
            weight_b=0.4,
        )
        fused_curves.append(ContinuousCurve(
            name="ambient_combined",
            source="both",
            points=combined,
        ))

    logger.info(
        "Fusion complete: %d events, %d continuous curves",
        len(fused_events),
        len(fused_curves),
    )

    return FusedFeatures(
        events=fused_events,
        curves=fused_curves,
        duration=duration,
    )


def _merge_curves(
    a: list[Tuple[float, float]],
    b: list[Tuple[float, float]],
    weight_a: float = 0.5,
    weight_b: float = 0.5,
) -> list[Tuple[float, float]]:
    """
    Merge two time-value curves into one using weighted interpolation.

    Both curves are resampled to a common set of time points (the union of both).
    """
    if not a and not b:
        return []
    if not a:
        return [(t, v * weight_b) for t, v in b]
    if not b:
        return [(t, v * weight_a) for t, v in a]

    # Collect all unique time points
    all_times = sorted(set(t for t, _ in a) | set(t for t, _ in b))

    a_times = np.array([t for t, _ in a])
    a_vals = np.array([v for _, v in a])
    b_times = np.array([t for t, _ in b])
    b_vals = np.array([v for _, v in b])

    merged = []
    for t in all_times:
        # Interpolate each curve at this time
        va = float(np.interp(t, a_times, a_vals))
        vb = float(np.interp(t, b_times, b_vals))
        merged_val = min(1.0, va * weight_a + vb * weight_b)
        merged.append((t, round(merged_val, 4)))

    return merged
