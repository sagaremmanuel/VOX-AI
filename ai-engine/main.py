"""
main.py  —  AI Engine FastAPI Server
-------------------------------------
The brain of the AI Android Assistant.

Endpoints:
  POST /speech_to_text   — Accepts an audio file, returns transcript JSON
  POST /parse_command    — Accepts a text transcript, returns intent + steps JSON
  GET  /health           — Health check for debugging

Architecture:
  Android App
      │
      │   POST /speech_to_text  (multipart audio file)
      ▼
  SpeechToText (faster-whisper)
      │   transcript text
      ▼
  IntentParser (rules + optional LLM)
      │   intent dict
      ▼
  TaskPlanner
      │   steps list
      ▼
  JSON response → Android TaskExecutor

Run:
  uvicorn main:app --host 0.0.0.0 --port 8000 --reload

Make sure your Android phone and laptop are on the same WiFi network.
Set the AI_ENGINE_BASE_URL in VoiceService.kt to your laptop's LAN IP.
"""

import logging
import os
import shutil
import tempfile
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from intent_parser import IntentParser
from llm_interface import LLMInterface
from speech_to_text import SpeechToText
from task_planner import TaskPlanner

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(name)s — %(message)s",
)
logger = logging.getLogger("ai_engine")

# ---------------------------------------------------------------------------
# App init
# ---------------------------------------------------------------------------
app = FastAPI(
    title="AI Android Assistant Engine",
    description="Offline speech & intent engine for the AI Voice Assistant Android app.",
    version="1.0.0",
)

# Allow Android app (running on device) to reach this server without CORS issues
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ---------------------------------------------------------------------------
# Component initialisation
# Loaded once at startup so inference is fast for every subsequent request.
# ---------------------------------------------------------------------------
USE_LLM = os.getenv("USE_LLM", "false").lower() == "true"
LLM_MODE = os.getenv("LLM_MODE", "ollama")          # "ollama" | "ctransformers" | "mock"
LLM_MODEL = os.getenv("LLM_MODEL", "mistral")
WHISPER_MODEL_SIZE = os.getenv("WHISPER_MODEL", "base")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cpu")

logger.info(f"Initialising Whisper ({WHISPER_MODEL_SIZE}) on {WHISPER_DEVICE}…")
stt = SpeechToText(model_size=WHISPER_MODEL_SIZE, device=WHISPER_DEVICE)
logger.info("Whisper ready.")

llm: LLMInterface | None = None
if USE_LLM:
    logger.info(f"Initialising LLM (mode={LLM_MODE}, model={LLM_MODEL})…")
    llm = LLMInterface(mode=LLM_MODE, model_name=LLM_MODEL)
    logger.info("LLM ready.")
else:
    logger.info("LLM disabled. Using rule-based parser only (faster).")

intent_parser = IntentParser(llm_interface=llm)
task_planner = TaskPlanner()

# ---------------------------------------------------------------------------
# Pydantic models
# ---------------------------------------------------------------------------

class TranscribedText(BaseModel):
    text: str
    language: str
    segments: list[dict]


class ParsedCommand(BaseModel):
    intent: str
    contact: str
    app: str
    time: str
    message_body: str = ""
    answer_text: str = ""
    steps: list[str]
    raw_text: str
    source: str


class ParseRequest(BaseModel):
    text: str
    language: str | None = None


class DescribeScreenRequest(BaseModel):
    screen_text: str


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------

@app.get("/health")
def health_check():
    """
    Quick liveness check.
    Use this to verify the server is reachable from the Android device:
        curl http://<your-laptop-ip>:8000/health
    """
    return {
        "status": "ok",
        "whisper_model": WHISPER_MODEL_SIZE,
        "llm_enabled": USE_LLM,
        "llm_mode": LLM_MODE if USE_LLM else None,
    }


@app.post("/speech_to_text", response_model=TranscribedText)
async def speech_to_text(audio: UploadFile = File(...)):
    """
    Convert an uploaded audio file to text using Whisper.

    - Accepts any audio format supported by FFmpeg (WAV, MP3, M4A, OGG, etc.)
    - Returns transcript, detected language, and timed segments.

    Android sends audio using OkHttp multipart:
        .addFormDataPart("audio", filename, requestBody)
    """
    if not audio.filename:
        raise HTTPException(status_code=400, detail="No file uploaded.")

    # Save upload to a temp file
    suffix = Path(audio.filename).suffix or ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        shutil.copyfileobj(audio.file, tmp)
        tmp_path = tmp.name

    try:
        logger.info(f"Received audio file: {audio.filename} ({os.path.getsize(tmp_path)} bytes)")
        result = stt.transcribe(tmp_path)
        logger.info(f"Transcript: {result['text']!r}")
        return result
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.exception("STT failed")
        raise HTTPException(status_code=500, detail=f"Transcription error: {e}")
    finally:
        os.unlink(tmp_path)


@app.post("/parse_command", response_model=ParsedCommand)
async def parse_command(request: ParseRequest):
    """
    Parse a text transcript into a structured command with execution steps.

    Request body:
        { "text": "Take the latest photo and send it to Rahul" }

    Response:
        {
            "intent": "send_photo",
            "contact": "Rahul",
            "app": "",
            "time": "",
            "steps": ["get_latest_photo", "open_whatsapp", "send_to_contact"],
            "raw_text": "Take the latest photo and send it to Rahul",
            "source": "rules"
        }
    """
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Empty text.")

    logger.info(f"Parsing command: {request.text!r}")

    # 1. Intent detection + entity extraction
    intent_dict = intent_parser.parse(request.text)

    # 2. Convert intent to step plan
    command = task_planner.plan(intent_dict)

    logger.info(f"Intent={command['intent']}, Steps={command['steps']}, Contact={command['contact']}")
    return command


@app.post("/describe_screen")
async def describe_screen(request: DescribeScreenRequest):
    """
    Summarize what is currently on the Android screen.

    Request body:
        { "screen_text": "<all visible text from accessibility tree>" }

    Response:
        { "summary": "You are looking at WhatsApp. There is a conversation with Rahul..." }
    """
    if not request.screen_text.strip():
        return {"summary": "The screen appears to be empty or no text was found."}

    logger.info(f"describe_screen: {len(request.screen_text)} chars of screen text")

    # Always use LLM (initialise on-demand if it was disabled for intent parsing)
    active_llm = llm
    if active_llm is None:
        active_llm = LLMInterface(mode="ollama", model_name="llama3.2:3b")

    summary = active_llm.summarize_screen(request.screen_text)
    logger.info(f"Screen summary: {summary!r}")
    return {"summary": summary}


@app.post("/full_pipeline")
async def full_pipeline(audio: UploadFile = File(...)):
    """
    Convenience endpoint: audio → transcript → parsed command (single round-trip).
    Useful for testing the complete pipeline without two separate calls.
    """
    # Step 1: STT
    stt_result = await speech_to_text(audio)
    if not stt_result.text.strip():
        return {"error": "No speech detected.", "transcript": ""}

    # Step 2: Parse
    parse_req = ParseRequest(text=stt_result.text)
    command = await parse_command(parse_req)
    return {
        "transcript": stt_result.text,
        "language": stt_result.language,
        **command.dict(),
    }


# ---------------------------------------------------------------------------
# Dev entry point
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        log_level="info",
    )
