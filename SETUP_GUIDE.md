# Setup Guide - AI Android Assistant

This guide is the fastest reliable path to run the project end-to-end.

## 1) Prerequisites

Laptop:
- Python 3.10+
- ffmpeg
- Android Studio
- ADB (Android platform-tools)

Phone:
- Android 8+
- Developer options + USB debugging enabled

## 2) Repository Layout

- `ai-engine/` -> FastAPI engine (Whisper + parser + planner)
- `android-app/` -> Android Kotlin app
- `models/whisper/` -> Whisper model cache
- `start_engine.sh` -> one-command engine startup

## 3) Engine Setup

From project root:

```bash
cd ai-engine
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Install ffmpeg if missing:

```bash
# macOS
brew install ffmpeg

# Ubuntu/Debian
sudo apt install ffmpeg
```

## 4) Start Engine

Recommended (root folder):

```bash
./start_engine.sh
```

Or manually:

```bash
cd ai-engine
source .venv/bin/activate
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

Verify:

```bash
curl http://127.0.0.1:8000/health
```

Expected: JSON with `"status":"ok"`.

## 5) Android App Setup

Open in Android Studio:
- Folder: `android-app/`

Then run once from terminal if needed:

```bash
cd android-app
./gradlew installDebug
```

Launch app:

```bash
adb shell am start -n com.aiassistant/.MainActivity
```

## 6) Connect App to Engine

Current app is configured for localhost engine URL in `VoiceService.kt`.
For physical device debugging, use USB reverse:

```bash
adb reverse tcp:8000 tcp:8000
adb reverse --list
```

If you prefer LAN mode instead:
- Set `AI_ENGINE_BASE_URL` in `android-app/app/src/main/java/com/aiassistant/VoiceService.kt`
- Use `http://<your-laptop-ip>:8000`
- Ensure phone and laptop are on same network

## 7) Runtime Checklist

1. Engine running (`/health` works)
2. `adb reverse` active (if localhost mode)
3. App installed and launched
4. Permissions granted (mic, contacts, phone, storage as prompted)

## 8) Quick Functional Test

Chat mode tests:
- Ask a normal question -> should reply in chat
- Send action command like `open youtube` -> should execute action path

Voice mode tests:
- Start voice -> trigger wake/listen flow
- Speak command -> parse and execute

## 9) Troubleshooting

### Cannot reach engine

```bash
curl http://127.0.0.1:8000/health
adb reverse tcp:8000 tcp:8000
adb shell am force-stop com.aiassistant
adb shell am start -n com.aiassistant/.MainActivity
```

### App says things twice

- Force-stop and relaunch app
- Confirm latest build is installed

### Chat mode not executing actions

- Ensure engine `/parse_command` returns intent/steps
- Confirm app is on latest build where typed commands route through `VoiceService.ACTION_PROCESS_TEXT`

### Build fails

```bash
cd android-app
./gradlew clean
./gradlew installDebug
```

## 10) Optional LLM (Ollama)

If you want richer parsing:

```bash
ollama serve
ollama pull llama3.2:3b
```

Then start engine with LLM enabled (script already handles this when available).
