"""
Haptic timeline generator.

Converts fused audio + video features into a structured haptic timeline JSON
compatible with the Android HapticTimeline data model and inspired by
Apple's AHAP format.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

logger = logging.getLogger("haptok.haptic_generator")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Minimum gap between transient events (seconds)
MIN_EVENT_GAP = 0.01
# Minimum percentage of timeline with silence (no haptics)
TARGET_SILENCE_RATIO = 0.30
# Smoothing window for intensity curves
SMOOTH_WINDOW = 5


def _clamp(value: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, value))


def _smooth(values: list[float], window: int = SMOOTH_WINDOW) -> list[float]:
    """Simple moving-average smoothing."""
    if len(values) <= window:
        return values
    smoothed = []
    for i in range(len(values)):
        start = max(0, i - window // 2)
        end = min(len(values), i + window // 2 + 1)
        smoothed.append(sum(values[start:end]) / (end - start))
    return smoothed


# ---------------------------------------------------------------------------
# Event classification → haptic mapping
# ---------------------------------------------------------------------------

# Map audio event types to haptic parameters
EVENT_HAPTIC_MAP: dict[str, dict[str, Any]] = {
    "explosion": {
        "intensity_range": (0.85, 1.0),
        "sharpness": 0.3,
        "tags": ["explosion"],
        "type": "transient",
        "duration": 0.5,
    },
    "gunshot": {
        "intensity_range": (0.8, 1.0),
        "sharpness": 0.9,
        "tags": ["gunshot"],
        "type": "transient",
        "duration": 0.12,
    },
    "engine": {
        "intensity_range": (0.3, 0.7),
        "sharpness": 0.2,
        "tags": ["engine_rumble"],
        "type": "continuous",
        "duration": 2.0,
    },
    "footstep": {
        "intensity_range": (0.25, 0.5),
        "sharpness": 0.5,
        "tags": ["footstep"],
        "type": "transient",
        "duration": 0.06,
    },
    "rain": {
        "intensity_range": (0.1, 0.3),
        "sharpness": 0.6,
        "tags": ["rain"],
        "type": "continuous",
        "duration": 3.0,
    },
    "impact": {
        "intensity_range": (0.6, 0.95),
        "sharpness": 0.7,
        "tags": ["impact"],
        "type": "transient",
        "duration": 0.15,
    },
    "music": {
        "intensity_range": (0.1, 0.4),
        "sharpness": 0.3,
        "tags": ["tension"],
        "type": "continuous",
        "duration": 2.0,
    },
    "wind": {
        "intensity_range": (0.05, 0.2),
        "sharpness": 0.4,
        "tags": ["rain"],
        "type": "continuous",
        "duration": 3.0,
    },
    "speech": {
        # Speech generally should NOT generate haptics
        "intensity_range": (0.0, 0.0),
        "sharpness": 0.0,
        "tags": [],
        "type": "none",
        "duration": 0,
    },
}


def _map_event_to_haptic(
    event_type: str, time_sec: float, confidence: float
) -> dict | None:
    """Convert a classified audio/video event to a haptic event dict."""
    mapping = EVENT_HAPTIC_MAP.get(event_type)
    if not mapping or mapping["type"] == "none":
        return None

    lo, hi = mapping["intensity_range"]
    intensity = _clamp(lo + (hi - lo) * confidence)

    if intensity < 0.05:
        return None

    return {
        "type": mapping["type"],
        "time_seconds": round(time_sec, 4),
        "duration_seconds": mapping["duration"] if mapping["type"] == "continuous" else None,
        "intensity": round(intensity, 3),
        "sharpness": mapping["sharpness"],
        "frequency_hz": None,
        "tags": mapping["tags"],
        "spatial_hint": None,
        "actuator_id": None,
    }


# ---------------------------------------------------------------------------
# Continuous curve builders
# ---------------------------------------------------------------------------


def _build_ambient_curve(
    fused: dict, duration: float
) -> list[dict]:
    """Build continuous ambient haptic curves from bass energy and camera shake."""
    curves = []

    # Sub-bass rumble curve
    bass_energy = fused.get("bass_energy_curve", [])
    if bass_energy:
        points = []
        for t, energy in bass_energy:
            if t <= duration:
                points.append({"time": round(t, 3), "value": round(_clamp(energy * 0.6), 3)})
        if points:
            curves.append({
                "parameter": "INTENSITY",
                "time_start": points[0]["time"],
                "time_end": points[-1]["time"],
                "control_points": points,
            })

    # Camera shake curve
    shake_data = fused.get("camera_shake_curve", [])
    if shake_data:
        points = []
        for t, magnitude in shake_data:
            if t <= duration:
                points.append({"time": round(t, 3), "value": round(_clamp(magnitude), 3)})
        if points:
            curves.append({
                "parameter": "INTENSITY",
                "time_start": points[0]["time"],
                "time_end": points[-1]["time"],
                "control_points": points,
            })

    return curves


# ---------------------------------------------------------------------------
# Post-processing
# ---------------------------------------------------------------------------


def _merge_close_events(events: list[dict], min_gap: float = MIN_EVENT_GAP) -> list[dict]:
    """Merge transient events that are too close together."""
    if not events:
        return events

    merged = [events[0]]
    for ev in events[1:]:
        prev = merged[-1]
        if ev["type"] == "transient" and prev["type"] == "transient":
            if ev["time_seconds"] - prev["time_seconds"] < min_gap:
                # Keep the stronger one
                if ev["intensity"] > prev["intensity"]:
                    merged[-1] = ev
                continue
        merged.append(ev)
    return merged


def _normalize_peaks(events: list[dict]) -> list[dict]:
    """Normalize so the strongest event has intensity 1.0."""
    if not events:
        return events
    max_intensity = max(ev["intensity"] for ev in events)
    if max_intensity <= 0:
        return events
    scale = 1.0 / max_intensity
    for ev in events:
        ev["intensity"] = round(_clamp(ev["intensity"] * scale), 3)
    return events


def _enforce_silence(
    events: list[dict], duration: float, target_ratio: float = TARGET_SILENCE_RATIO
) -> list[dict]:
    """
    Remove low-intensity events if too much of the timeline is 'active'.
    This ensures the haptics breathe and don't feel like constant buzzing.
    """
    if not events or duration <= 0:
        return events

    # Calculate active duration
    active_time = sum(
        (ev.get("duration_seconds") or 0.05) for ev in events
    )
    active_ratio = active_time / duration

    if active_ratio <= (1.0 - target_ratio):
        return events  # Already enough silence

    # Sort by intensity and remove weakest events until target met
    events_sorted = sorted(events, key=lambda e: e["intensity"])
    while active_ratio > (1.0 - target_ratio) and events_sorted:
        removed = events_sorted.pop(0)
        active_time -= removed.get("duration_seconds") or 0.05
        active_ratio = active_time / duration
        events.remove(removed)

    return events


# ---------------------------------------------------------------------------
# Main generator
# ---------------------------------------------------------------------------


def generate(
    fused_features: dict,
    device_profile: dict,
    duration: float,
) -> dict:
    """
    Generate a complete haptic timeline from fused multimodal features.

    Args:
        fused_features: Output from multimodal_fusion.fuse()
        device_profile: Android device haptic capabilities
        duration: Video duration in seconds

    Returns:
        HapticTimeline dict matching the Pydantic schema
    """
    logger.info("Generating haptic timeline for %.1fs video", duration)

    impact_events: list[dict] = []
    ambient_events: list[dict] = []
    texture_events: list[dict] = []
    motion_events: list[dict] = []

    # ── 1. Process classified events ──────────────────────────────────
    for event in fused_features.get("events", []):
        t = event.get("time", 0)
        etype = event.get("type", "unknown")
        conf = event.get("confidence", 0.5)
        source = event.get("source", "audio")

        haptic = _map_event_to_haptic(etype, t, conf)
        if haptic is None:
            continue

        if haptic["type"] == "transient":
            impact_events.append(haptic)
        elif etype in ("rain", "wind"):
            texture_events.append(haptic)
        else:
            ambient_events.append(haptic)

    # ── 2. Process onset events (audio transients) ────────────────────
    for onset in fused_features.get("onset_events", []):
        t = onset.get("time", 0)
        strength = onset.get("strength", 0.5)
        if strength < 0.15:
            continue
        impact_events.append({
            "type": "transient",
            "time_seconds": round(t, 4),
            "duration_seconds": None,
            "intensity": round(_clamp(strength), 3),
            "sharpness": round(_clamp(0.4 + strength * 0.5), 3),
            "frequency_hz": None,
            "tags": ["impact"],
            "spatial_hint": None,
            "actuator_id": None,
        })

    # ── 3. Process motion/camera shake as continuous ──────────────────
    for motion in fused_features.get("motion_events", []):
        t = motion.get("time", 0)
        intensity = motion.get("intensity", 0)
        if intensity < 0.1:
            continue
        motion_events.append({
            "type": "continuous",
            "time_seconds": round(t, 4),
            "duration_seconds": 1.0,
            "intensity": round(_clamp(intensity * 0.5), 3),
            "sharpness": 0.3,
            "frequency_hz": None,
            "tags": ["camera_shake"],
            "spatial_hint": None,
            "actuator_id": None,
        })

    # ── 4. Scene change transients ────────────────────────────────────
    for sc_time in fused_features.get("scene_changes", []):
        impact_events.append({
            "type": "transient",
            "time_seconds": round(sc_time, 4),
            "duration_seconds": None,
            "intensity": 0.4,
            "sharpness": 0.6,
            "frequency_hz": None,
            "tags": ["impact"],
            "spatial_hint": None,
            "actuator_id": None,
        })

    # ── 5. Sort and post-process ──────────────────────────────────────
    impact_events.sort(key=lambda e: e["time_seconds"])
    impact_events = _merge_close_events(impact_events)
    impact_events = _normalize_peaks(impact_events)
    impact_events = _enforce_silence(impact_events, duration)

    ambient_events.sort(key=lambda e: e["time_seconds"])
    texture_events.sort(key=lambda e: e["time_seconds"])
    motion_events.sort(key=lambda e: e["time_seconds"])

    # ── 6. Build ambient curves ───────────────────────────────────────
    ambient_curves = _build_ambient_curve(fused_features, duration)

    # ── 7. Assemble timeline ──────────────────────────────────────────
    tracks = []
    if impact_events:
        tracks.append({
            "name": "impacts",
            "category": "TRANSIENT",
            "events": impact_events,
            "curves": [],
        })
    if ambient_events or ambient_curves:
        tracks.append({
            "name": "ambient",
            "category": "CONTINUOUS",
            "events": ambient_events,
            "curves": ambient_curves,
        })
    if texture_events:
        tracks.append({
            "name": "texture",
            "category": "TEXTURE",
            "events": texture_events,
            "curves": [],
        })
    if motion_events:
        tracks.append({
            "name": "motion",
            "category": "CONTINUOUS",
            "events": motion_events,
            "curves": [],
        })

    total_events = sum(len(t["events"]) for t in tracks)
    logger.info(
        "Generated %d tracks with %d total events for %.1fs video",
        len(tracks), total_events, duration,
    )

    return {
        "version": "1.0",
        "source_video": "input",
        "duration_seconds": round(duration, 3),
        "sample_rate_hz": 100,
        "tracks": tracks,
        "metadata": {
            "models_used": ["rule_based_v1", "spectral_flux", "farneback_flow"],
            "generation_timestamp": datetime.now(timezone.utc).isoformat(),
            "confidence_scores": {"audio": 0.85, "video": 0.75},
        },
    }
