#!/usr/bin/env bash
# start_engine.sh — one-command startup for the AI engine
# Usage: ./start_engine.sh [whisper_model]
# Examples:
#   ./start_engine.sh        → base Whisper + llama3.2:3b LLM (default)
#   ./start_engine.sh small  → small Whisper + llama3.2:3b LLM

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENGINE_DIR="$SCRIPT_DIR/ai-engine"

WHISPER_MODEL="${1:-base}"
LLM_MODEL="${LLM_MODEL:-llama3.2:3b}"

# Locate ollama binary
if command -v ollama &>/dev/null; then
    OLLAMA_BIN="ollama"
elif [[ -x "/Applications/Ollama.app/Contents/Resources/ollama" ]]; then
    OLLAMA_BIN="/Applications/Ollama.app/Contents/Resources/ollama"
else
    OLLAMA_BIN=""
fi

echo "================================================"
echo "  AI Android Assistant — Engine Startup"
echo "================================================"
echo "  Whisper model : $WHISPER_MODEL"
echo "  LLM model     : $LLM_MODEL"
echo ""

# Activate virtual environment if it exists
if [[ -d "$ENGINE_DIR/.venv" ]]; then
    source "$ENGINE_DIR/.venv/bin/activate"
    echo "  Python env    : $ENGINE_DIR/.venv"
else
    echo "  [Warning] No .venv found. Using system Python."
    echo "  Create one with: python -m venv ai-engine/.venv && pip install -r ai-engine/requirements.txt"
fi

# Print local IP for reference
LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}' || echo "unknown")
echo ""
echo "  Your LAN IP   : $LOCAL_IP"
echo "  API base URL  : http://$LOCAL_IP:8000"
echo ""
echo "  Set this in VoiceService.kt:"
echo "    const val AI_ENGINE_BASE_URL = \"http://$LOCAL_IP:8000\""
echo ""
echo "================================================"
echo ""

# ---- Start Ollama if not already running --------------------------------
if [[ -n "$OLLAMA_BIN" ]]; then
    if ! curl -s --max-time 1 http://localhost:11434 &>/dev/null; then
        echo "  [Ollama] Starting daemon..."
        nohup "$OLLAMA_BIN" serve > /tmp/ollama.log 2>&1 &
        # Wait up to 8 s for Ollama to become ready
        for i in {1..8}; do
            sleep 1
            if curl -s --max-time 1 http://localhost:11434 &>/dev/null; then
                echo "  [Ollama] Ready."
                break
            fi
        done
    else
        echo "  [Ollama] Already running."
    fi

    # Ensure model is pulled (no-op if already present)
    echo "  [Ollama] Verifying model $LLM_MODEL is available..."
    "$OLLAMA_BIN" pull "$LLM_MODEL" 2>/dev/null | grep -E 'pulling|success|already' || true
else
    echo "  [Warning] Ollama not found. LLM disabled."
    LLM_MODEL=""
fi

echo ""
echo "  Starting FastAPI server..."
echo ""

# ---- Launch FastAPI engine ----------------------------------------------
cd "$ENGINE_DIR"

USE_LLM_FLAG="false"
[[ -n "$LLM_MODEL" ]] && USE_LLM_FLAG="true"

USE_LLM="$USE_LLM_FLAG" \
LLM_MODE="ollama" \
LLM_MODEL="$LLM_MODEL" \
WHISPER_MODEL="$WHISPER_MODEL" \
    uvicorn main:app \
    --host 0.0.0.0 \
    --port 8000 \
    --reload \
    --log-level info
