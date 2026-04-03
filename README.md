# AI Android Assistant

Local-first Android assistant with voice + chat control.

- Android app: Kotlin, foreground voice service, command execution.
- AI engine: FastAPI + Whisper STT + intent parsing + step planning.
- Optional LLM fallback via Ollama.

## What It Does

- Wake-word voice flow (Hey Vox -> listen -> execute).
- Chat mode with typed input.
- Typed commands in chat mode can execute real device actions.
- Themeable UI with drawer-based theme switching.
- Haptic and TTS feedback.

## High-Level Flow

1. Input from voice or chat.
2. Engine parses intent through /parse_command.
3. Android executes planned steps (TaskExecutor).
4. UI receives logs/state/TTS broadcasts and updates in real-time.

## Project Structure

AI-Android-Assistant/
- android-app/
  - app/src/main/java/com/aiassistant/
    - MainActivity.kt
    - VoiceService.kt
    - CommandProcessor.kt
    - TaskExecutor.kt
    - BackendActivity.kt
  - app/src/main/res/
- ai-engine/
  - main.py
  - speech_to_text.py
  - intent_parser.py
  - task_planner.py
  - llm_interface.py
  - requirements.txt
- models/
  - whisper/
- start_engine.sh
- SETUP_GUIDE.md

## Quick Start

### 1) Start AI Engine

From repository root:

```bash
./start_engine.sh
```

Health check:

```bash
curl http://127.0.0.1:8000/health
```

### 2) Connect Android App to Engine

Current app base URL is localhost in VoiceService.kt.

For USB debugging, run:

```bash
adb reverse tcp:8000 tcp:8000
```

Then install/run app:

```bash
cd android-app
./gradlew installDebug
adb shell am start -n com.aiassistant/.MainActivity
```

If using LAN instead of adb reverse, set AI_ENGINE_BASE_URL in VoiceService.kt to your machine IP.

## Engine Configuration

Environment variables:

- WHISPER_MODEL (default: base)
- WHISPER_DEVICE (default: cpu)
- USE_LLM (default: false unless start script enables with Ollama)
- LLM_MODE (ollama | ctransformers | mock)
- LLM_MODEL (example: llama3.2:3b)

Example manual run:

```bash
cd ai-engine
source .venv/bin/activate
USE_LLM=true LLM_MODE=ollama LLM_MODEL=llama3.2:3b uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

## API Endpoints

- GET /health
- POST /speech_to_text
- POST /parse_command
- POST /describe_screen
- POST /full_pipeline

## Notes on Current App Behavior

- Chat mode supports both conversational replies and functional command execution.
- Thinking indicator is visible in chat panel while waiting for engine response.
- MainActivity broadcast receiver is lifecycle-scoped (onStart/onStop) to prevent duplicate handling.

## Troubleshooting

### Cannot reach engine

1. Confirm engine: curl http://127.0.0.1:8000/health
2. Ensure adb reverse: adb reverse tcp:8000 tcp:8000
3. Relaunch app process:

```bash
adb shell am force-stop com.aiassistant
adb shell am start -n com.aiassistant/.MainActivity
```

### Duplicate responses/speech

- Force-stop and restart app once after updates.
- Ensure only one app instance is active.

## Detailed Setup

For full machine setup, dependencies, device prep, and demo checklist, see SETUP_GUIDE.md.
