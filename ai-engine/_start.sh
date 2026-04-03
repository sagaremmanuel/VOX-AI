#!/usr/bin/env bash
cd "$(dirname "$0")"
source .venv/bin/activate
export USE_LLM=true
export LLM_MODE=ollama
export LLM_MODEL=llama3.2:3b
export WHISPER_MODEL=base
export WHISPER_DEVICE=cpu
nohup uvicorn main:app --host 0.0.0.0 --port 8000 --log-level info > /tmp/engine.log 2>&1 &
echo "Server PID: $!"
