"""
Haptok Haptic Timeline Schemas

Pydantic v2 models that mirror the Android-side Kotlin data classes.
Uses AHAP-inspired structure with tracks, events, curves, and control points.
"""

from __future__ import annotations

import uuid
from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field, model_validator


# ──────────────────────────────────────────────────────────────────────────────
# Enumerations
# ──────────────────────────────────────────────────────────────────────────────

class HapticEventType(str, Enum):
    """Types of discrete haptic events."""
    TRANSIENT = "transient"
    CONTINUOUS = "continuous"


class HapticParameterID(str, Enum):
    """Identifiers for haptic parameters (Apple AHAP compatible)."""
    INTENSITY = "HapticIntensity"
    SHARPNESS = "HapticSharpness"


class TrackType(str, Enum):
    """Semantic track categories."""
    IMPACTS = "impacts"
    AMBIENT = "ambient"
    TEXTURE = "texture"
    MOTION = "motion"


class JobStatusEnum(str, Enum):
    """Processing pipeline status values."""
    QUEUED = "queued"
    EXTRACTING = "extracting"
    ANALYZING_AUDIO = "analyzing_audio"
    ANALYZING_VIDEO = "analyzing_video"
    FUSING = "fusing"
    GENERATING = "generating"
    COMPLETE = "complete"
    FAILED = "failed"


# ──────────────────────────────────────────────────────────────────────────────
# Haptic primitives
# ──────────────────────────────────────────────────────────────────────────────

class ControlPoint(BaseModel):
    """A single point on a haptic parameter curve."""
    model_config = {"populate_by_name": True}

    time: float = Field(..., description="Time in seconds from track start", alias="Time")
    value: float = Field(..., ge=0.0, le=1.0, description="Parameter value 0-1", alias="Value")


class HapticCurve(BaseModel):
    """A continuous parameter curve defined by control points."""
    model_config = {"populate_by_name": True}

    parameter_id: HapticParameterID = Field(
        ..., description="Which parameter this curve controls", alias="ParameterID"
    )
    control_points: list[ControlPoint] = Field(
        default_factory=list, description="Ordered control points", alias="ParameterCurve"
    )


class HapticEvent(BaseModel):
    """A discrete or continuous haptic event."""
    model_config = {"populate_by_name": True}

    time: float = Field(..., description="Start time in seconds", alias="Time")
    event_type: HapticEventType = Field(..., description="transient or continuous", alias="EventType")
    duration: Optional[float] = Field(
        None, ge=0.0, description="Duration for continuous events (seconds)", alias="EventDuration"
    )
    intensity: float = Field(0.5, ge=0.0, le=1.0, description="Intensity 0-1", alias="Intensity")
    sharpness: float = Field(0.5, ge=0.0, le=1.0, description="Sharpness 0-1", alias="Sharpness")
    tags: list[str] = Field(default_factory=list, description="Semantic tags", alias="Tags")
    curves: list[HapticCurve] = Field(
        default_factory=list, description="Parameter curves for continuous events", alias="EventCurves"
    )


class HapticTrack(BaseModel):
    """A named track containing haptic events and curves."""
    model_config = {"populate_by_name": True}

    track_type: TrackType = Field(..., description="Semantic track category", alias="TrackType")
    label: str = Field("", description="Human-readable label", alias="Label")
    events: list[HapticEvent] = Field(default_factory=list, alias="Events")
    curves: list[HapticCurve] = Field(
        default_factory=list, description="Track-level parameter curves", alias="Curves"
    )
    enabled: bool = Field(True, description="Whether this track is active", alias="Enabled")


# ──────────────────────────────────────────────────────────────────────────────
# Device profile
# ──────────────────────────────────────────────────────────────────────────────

class DeviceProfile(BaseModel):
    """Describes the haptic capabilities of the target device."""
    model_config = {"populate_by_name": True}

    device_name: str = Field("generic", alias="deviceName")
    has_haptic_engine: bool = Field(True, alias="hasHapticEngine")
    max_intensity: float = Field(1.0, ge=0.0, le=1.0, alias="maxIntensity")
    supported_frequencies: list[float] = Field(
        default_factory=lambda: [0.0, 300.0], alias="supportedFrequencies"
    )
    supports_transient: bool = Field(True, alias="supportsTransient")
    supports_continuous: bool = Field(True, alias="supportsContinuous")
    max_simultaneous_channels: int = Field(2, ge=1, alias="maxSimultaneousChannels")


# ──────────────────────────────────────────────────────────────────────────────
# Processing metadata & timeline
# ──────────────────────────────────────────────────────────────────────────────

class ProcessingMetadata(BaseModel):
    """Metadata about how the haptic timeline was generated."""
    model_config = {"populate_by_name": True}

    pipeline_version: str = Field("1.0.0", alias="pipelineVersion")
    audio_analyzer: str = Field("rule_based_v1", alias="audioAnalyzer")
    video_analyzer: str = Field("opencv_farneback_v1", alias="videoAnalyzer")
    fusion_strategy: str = Field("weighted_merge_v1", alias="fusionStrategy")
    processing_time_ms: float = Field(0.0, ge=0.0, alias="processingTimeMs")
    device_profile_used: Optional[DeviceProfile] = Field(None, alias="deviceProfileUsed")


class HapticTimeline(BaseModel):
    """
    Top-level haptic timeline document.
    Inspired by Apple's AHAP format, extended with multi-track support.
    """
    model_config = {"populate_by_name": True}

    version: str = Field("1.0", alias="Version")
    duration: float = Field(..., ge=0.0, description="Total duration in seconds", alias="Duration")
    tracks: list[HapticTrack] = Field(default_factory=list, alias="Tracks")
    metadata: ProcessingMetadata = Field(default_factory=ProcessingMetadata, alias="Metadata")


# ──────────────────────────────────────────────────────────────────────────────
# API response models
# ──────────────────────────────────────────────────────────────────────────────

class UploadResponse(BaseModel):
    """Returned immediately after a successful video upload."""
    model_config = {"populate_by_name": True}

    job_id: str = Field(
        default_factory=lambda: str(uuid.uuid4()),
        description="Unique job identifier",
        alias="jobId",
    )
    status: JobStatusEnum = Field(JobStatusEnum.QUEUED, alias="status")
    message: str = Field("Video uploaded successfully. Processing started.", alias="message")


class JobStatus(BaseModel):
    """Current status of a processing job."""
    model_config = {"populate_by_name": True}

    job_id: str = Field(..., alias="jobId")
    status: JobStatusEnum = Field(..., alias="status")
    progress: float = Field(0.0, ge=0.0, le=1.0, description="Progress 0-1", alias="progress")
    message: str = Field("", alias="message")
    error: Optional[str] = Field(None, alias="error")
    result_ready: bool = Field(False, alias="resultReady")
