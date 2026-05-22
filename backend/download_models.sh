#!/bin/bash
# ============================================================================
# Haptok — AI Model Download Script
# ============================================================================
# Downloads pre-trained models for enhanced audio/video analysis.
# The system works WITHOUT these models (rule-based fallback), but
# AI models provide significantly better haptic quality.
# ============================================================================

set -e

MODELS_DIR="${MODELS_DIR:-/opt/haptok/models}"

echo "╔══════════════════════════════════════════════════════════╗"
echo "║           Haptok — AI Model Downloader                  ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "Models directory: $MODELS_DIR"
echo ""

mkdir -p "$MODELS_DIR"

# ---------------------------------------------------------------------------
# Audio Spectrogram Transformer (AST)
# ---------------------------------------------------------------------------
echo "── Audio Spectrogram Transformer (AST) ──"
echo ""
echo "The AST model is loaded via HuggingFace Transformers at runtime."
echo "On first use, it will auto-download (~300MB)."
echo ""
echo "To pre-download:"
echo "  pip install transformers"
echo "  python -c \"from transformers import AutoModel; AutoModel.from_pretrained('MIT/ast-finetuned-audioset-10-10-0.4593')\""
echo ""

# ---------------------------------------------------------------------------
# SlowFast (Video Action Recognition)
# ---------------------------------------------------------------------------
echo "── SlowFast (Video Action Recognition) ──"
echo ""
echo "SlowFast is loaded via PyTorchVideo / torch.hub at runtime."
echo "On first use, it will auto-download (~200MB)."
echo ""
echo "To pre-download:"
echo "  python -c \"import torch; torch.hub.load('facebookresearch/pytorchvideo', 'slowfast_r50', pretrained=True)\""
echo ""

# ---------------------------------------------------------------------------
# RAFT (Optical Flow)
# ---------------------------------------------------------------------------
echo "── RAFT (Optical Flow) ──"
echo ""
echo "Phase 1 uses OpenCV Farneback flow (no model download needed)."
echo "For Phase 2+ with RAFT:"
echo "  git clone https://github.com/princeton-vl/RAFT.git $MODELS_DIR/raft"
echo "  Download weights from: https://drive.google.com/drive/folders/1sWDsfuZ3Up38EUQt7-JDTT1HcGHuJgvT"
echo ""

echo "════════════════════════════════════════════════════════════"
echo "Done! The system will work in rule-based mode immediately."
echo "AI models will be downloaded on first use if network is available."
echo "════════════════════════════════════════════════════════════"
