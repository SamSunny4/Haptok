"""
Haptok Audio Analyzer

Extracts audio features for haptic generation:
- Onset detection via spectral flux (librosa)
- Frequency band energies (sub-bass, bass, mid, high)
- Amplitude envelope
- Rule-based event classification (Phase 1 – replaces AST model)

Phase 2 TODO: Load AST (Audio Spectrogram Transformer) from
  {MODELS_DIR}/ast_audioset.pth for learned event classification.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Tuple

import librosa
import numpy as np
import numpy.typing as npt

import config

logger = logging.getLogger("haptok.audio")


# ──────────────────────────────────────────────────────────────────────────────
# Output data structure
# ──────────────────────────────────────────────────────────────────────────────

@dataclass
class AudioFeatures:
    """Container for all extracted audio features."""
    onset_times: list[float] = field(default_factory=list)
    onset_strengths: list[float] = field(default_factory=list)
    band_energies: dict[str, list[Tuple[float, float]]] = field(default_factory=dict)
    events: list[Tuple[float, str, float]] = field(default_factory=list)  # (time, type, confidence)
    amplitude_envelope: list[Tuple[float, float]] = field(default_factory=list)
    duration: float = 0.0


# ──────────────────────────────────────────────────────────────────────────────
# Audio loading
# ──────────────────────────────────────────────────────────────────────────────

def load_audio(path: str, sr: int = config.AUDIO_SAMPLE_RATE) -> Tuple[npt.NDArray[np.float32], int]:
    """
    Load an audio file and resample to the target sample rate.

    Uses librosa (backed by soundfile) so we don't require torchaudio
    at analysis time — keeps the worker lighter.
    """
    logger.info("Loading audio: %s (target sr=%d)", path, sr)
    waveform, sample_rate = librosa.load(path, sr=sr, mono=True)
    logger.info("Loaded audio: %.2fs, %d samples", len(waveform) / sample_rate, len(waveform))
    return waveform, sample_rate


# ──────────────────────────────────────────────────────────────────────────────
# Feature extraction
# ──────────────────────────────────────────────────────────────────────────────

def extract_mel_spectrogram(
    waveform: npt.NDArray[np.float32],
    sr: int = config.AUDIO_SAMPLE_RATE,
) -> npt.NDArray[np.float32]:
    """Compute a log-power mel spectrogram."""
    S = librosa.feature.melspectrogram(
        y=waveform,
        sr=sr,
        n_fft=config.MEL_N_FFT,
        hop_length=config.MEL_HOP_LENGTH,
        n_mels=config.MEL_N_MELS,
    )
    return librosa.power_to_db(S, ref=np.max)


def detect_onsets(
    waveform: npt.NDArray[np.float32],
    sr: int = config.AUDIO_SAMPLE_RATE,
) -> Tuple[npt.NDArray[np.float64], npt.NDArray[np.float64]]:
    """
    Detect onsets using spectral flux.

    Returns:
        onset_times: array of onset times in seconds
        onset_strengths: array of corresponding onset strength values (0-1 normalised)
    """
    onset_env = librosa.onset.onset_strength(y=waveform, sr=sr, hop_length=config.MEL_HOP_LENGTH)
    onset_frames = librosa.onset.onset_detect(
        y=waveform,
        sr=sr,
        hop_length=config.MEL_HOP_LENGTH,
        onset_envelope=onset_env,
        backtrack=True,
    )
    onset_times = librosa.frames_to_time(onset_frames, sr=sr, hop_length=config.MEL_HOP_LENGTH)

    # Normalise strengths to 0-1
    strengths = onset_env[onset_frames] if len(onset_frames) > 0 else np.array([])
    if len(strengths) > 0 and strengths.max() > 0:
        strengths = strengths / strengths.max()

    return onset_times, strengths


def extract_band_energies(
    waveform: npt.NDArray[np.float32],
    sr: int = config.AUDIO_SAMPLE_RATE,
    window_sec: float = config.ANALYSIS_WINDOW_SEC,
) -> dict[str, list[Tuple[float, float]]]:
    """
    Extract energy in four frequency bands over time windows.

    Bands:
        sub_bass:  20 – 80 Hz
        bass:      80 – 250 Hz
        mid:       250 – 4000 Hz
        high:      4000 – 8000 Hz (capped at Nyquist)
    """
    nyquist = sr / 2.0
    bands = {
        "sub_bass": (20, min(80, nyquist)),
        "bass": (80, min(250, nyquist)),
        "mid": (250, min(4000, nyquist)),
        "high": (4000, min(8000, nyquist)),
    }

    # STFT
    S = np.abs(librosa.stft(waveform, n_fft=config.MEL_N_FFT, hop_length=config.MEL_HOP_LENGTH))
    freqs = librosa.fft_frequencies(sr=sr, n_fft=config.MEL_N_FFT)

    window_frames = max(1, int(window_sec * sr / config.MEL_HOP_LENGTH))

    result: dict[str, list[Tuple[float, float]]] = {}
    for band_name, (lo, hi) in bands.items():
        # Select frequency bins within this band
        mask = (freqs >= lo) & (freqs < hi)
        band_power = S[mask, :] ** 2  # power

        energies: list[Tuple[float, float]] = []
        n_frames = band_power.shape[1]
        for start in range(0, n_frames, window_frames):
            end = min(start + window_frames, n_frames)
            t = librosa.frames_to_time(start, sr=sr, hop_length=config.MEL_HOP_LENGTH)
            energy = float(np.mean(band_power[:, start:end]))
            energies.append((t, energy))

        # Normalise per band to 0-1
        if energies:
            max_e = max(e for _, e in energies)
            if max_e > 0:
                energies = [(t, e / max_e) for t, e in energies]

        result[band_name] = energies

    return result


def extract_amplitude_envelope(
    waveform: npt.NDArray[np.float32],
    sr: int = config.AUDIO_SAMPLE_RATE,
    window_sec: float = config.ANALYSIS_WINDOW_SEC,
) -> list[Tuple[float, float]]:
    """Compute RMS amplitude envelope over time windows."""
    frame_length = int(window_sec * sr)
    hop_length = frame_length  # non-overlapping
    rms = librosa.feature.rms(y=waveform, frame_length=frame_length, hop_length=hop_length)[0]

    # Normalise to 0-1
    if rms.max() > 0:
        rms = rms / rms.max()

    envelope = []
    for i, val in enumerate(rms):
        t = i * window_sec
        envelope.append((t, float(val)))
    return envelope


# ──────────────────────────────────────────────────────────────────────────────
# Rule-based event classification (Phase 1)
# ──────────────────────────────────────────────────────────────────────────────

# Phase 2 TODO: Replace this with AST model inference:
#   model_path = Path(config.MODELS_DIR) / "ast_audioset.pth"
#   Load model, run mel_spec through it, return top-k class predictions per window.

_EVENT_CLASSES = [
    "explosion", "gunshot", "engine", "footstep", "rain",
    "music", "speech", "silence", "impact", "wind",
]


def classify_events(
    waveform: npt.NDArray[np.float32],
    sr: int = config.AUDIO_SAMPLE_RATE,
    band_energies: dict[str, list[Tuple[float, float]]] | None = None,
    window_sec: float = config.ANALYSIS_WINDOW_SEC,
) -> list[Tuple[float, str, float]]:
    """
    Rule-based audio event classifier (Phase 1).

    Analyses frequency content per window to produce event labels with
    confidence scores.  This provides a working end-to-end pipeline without
    requiring any model weights.

    Classification heuristics:
        explosion   – high sub-bass + broadband energy
        gunshot     – sharp transient + broadband energy
        engine      – sustained sub-bass, low mid, low high
        footstep    – periodic mid-freq transients
        rain        – sustained high-freq noise, low sub-bass
        music       – balanced spectrum
        speech      – dominant mid band
        wind        – sustained mid + high, low sub-bass
        impact      – broadband transient
        silence     – very low overall energy
    """
    if band_energies is None:
        band_energies = extract_band_energies(waveform, sr, window_sec)

    n_windows = min(len(v) for v in band_energies.values()) if band_energies else 0
    events: list[Tuple[float, str, float]] = []

    # Compute transient indicator per window
    rms = librosa.feature.rms(y=waveform, frame_length=int(window_sec * sr), hop_length=int(window_sec * sr))[0]
    rms_norm = rms / rms.max() if rms.max() > 0 else rms

    # Onset density per window (indicates transient-rich segments)
    onset_env = librosa.onset.onset_strength(y=waveform, sr=sr, hop_length=config.MEL_HOP_LENGTH)
    frames_per_window = max(1, int(window_sec * sr / config.MEL_HOP_LENGTH))
    onset_density = []
    for i in range(0, len(onset_env), frames_per_window):
        chunk = onset_env[i : i + frames_per_window]
        onset_density.append(float(np.mean(chunk)) if len(chunk) > 0 else 0.0)
    max_od = max(onset_density) if onset_density else 1.0
    if max_od > 0:
        onset_density = [v / max_od for v in onset_density]

    for i in range(n_windows):
        t = band_energies["sub_bass"][i][0]
        sb = band_energies["sub_bass"][i][1]
        ba = band_energies["bass"][i][1]
        mi = band_energies["mid"][i][1]
        hi = band_energies["high"][i][1]
        total = sb + ba + mi + hi
        od = onset_density[i] if i < len(onset_density) else 0.0
        rms_val = float(rms_norm[i]) if i < len(rms_norm) else 0.0

        # ── silence ──
        if total < 0.15 and rms_val < 0.1:
            events.append((t, "silence", 0.9))
            continue

        # ── explosion: high sub-bass + broadband + high onset density ──
        if sb > 0.6 and total > 2.0 and od > 0.5:
            conf = min(1.0, (sb * 0.4 + total / 4 * 0.3 + od * 0.3))
            events.append((t, "explosion", round(conf, 3)))
            continue

        # ── gunshot: very sharp transient + broadband ──
        if od > 0.7 and total > 1.5 and hi > 0.4:
            conf = min(1.0, (od * 0.5 + hi * 0.3 + ba * 0.2))
            events.append((t, "gunshot", round(conf, 3)))
            continue

        # ── impact: broadband transient but not as extreme ──
        if od > 0.5 and total > 1.2:
            conf = min(1.0, (od * 0.5 + total / 4 * 0.5))
            events.append((t, "impact", round(conf, 3)))
            continue

        # ── engine: sustained sub-bass, low high ──
        if sb > 0.5 and hi < 0.3 and od < 0.3:
            conf = min(1.0, (sb * 0.5 + (1 - hi) * 0.3 + (1 - od) * 0.2))
            events.append((t, "engine", round(conf, 3)))
            continue

        # ── rain: sustained high-freq, low sub-bass ──
        if hi > 0.5 and sb < 0.2 and od < 0.3:
            conf = min(1.0, (hi * 0.5 + (1 - sb) * 0.3 + (1 - od) * 0.2))
            events.append((t, "rain", round(conf, 3)))
            continue

        # ── wind: sustained mid+high, low sub-bass ──
        if (mi + hi) > 1.0 and sb < 0.2 and od < 0.25:
            conf = min(1.0, ((mi + hi) / 2 * 0.5 + (1 - sb) * 0.3 + (1 - od) * 0.2))
            events.append((t, "wind", round(conf, 3)))
            continue

        # ── footstep: periodic mid-freq transients ──
        if mi > 0.4 and od > 0.3 and sb < 0.3 and hi < 0.4:
            conf = min(1.0, (mi * 0.4 + od * 0.3 + (1 - sb) * 0.3))
            events.append((t, "footstep", round(conf, 3)))
            continue

        # ── speech: dominant mid band ──
        if mi > 0.5 and mi > sb and mi > hi:
            conf = min(1.0, (mi * 0.5 + (1 - sb) * 0.25 + (1 - hi) * 0.25))
            events.append((t, "speech", round(conf, 3)))
            continue

        # ── music: balanced spectrum ──
        balance = 1.0 - np.std([sb, ba, mi, hi])
        if balance > 0.6 and total > 0.8:
            events.append((t, "music", round(float(balance * 0.7 + total / 4 * 0.3), 3)))
            continue

        # ── fallback: label as the dominant band ──
        dominant_band = max(
            [("sub_bass", sb), ("bass", ba), ("mid", mi), ("high", hi)],
            key=lambda x: x[1],
        )
        events.append((t, f"unknown_{dominant_band[0]}", round(float(dominant_band[1]), 3)))

    logger.info("Classified %d windows into events", len(events))
    return events


# ──────────────────────────────────────────────────────────────────────────────
# Main analysis entry point
# ──────────────────────────────────────────────────────────────────────────────

def analyze(audio_path: str) -> AudioFeatures:
    """
    Run the complete audio analysis pipeline.

    Args:
        audio_path: Path to the extracted audio file (WAV/FLAC/MP3).

    Returns:
        AudioFeatures with onsets, band energies, events, and amplitude envelope.
    """
    logger.info("Starting audio analysis: %s", audio_path)
    waveform, sr = load_audio(audio_path)
    duration = float(len(waveform) / sr)

    # Onset detection
    onset_times, onset_strengths = detect_onsets(waveform, sr)

    # Band energies
    band_energies = extract_band_energies(waveform, sr)

    # Amplitude envelope
    amplitude_envelope = extract_amplitude_envelope(waveform, sr)

    # Event classification (rule-based Phase 1)
    events = classify_events(waveform, sr, band_energies)

    features = AudioFeatures(
        onset_times=onset_times.tolist(),
        onset_strengths=onset_strengths.tolist(),
        band_energies=band_energies,
        events=events,
        amplitude_envelope=amplitude_envelope,
        duration=duration,
    )

    logger.info(
        "Audio analysis complete: duration=%.2fs, onsets=%d, events=%d",
        duration,
        len(features.onset_times),
        len(features.events),
    )
    return features
