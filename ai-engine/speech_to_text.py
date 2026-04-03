"""
speech_to_text.py
-----------------
Offline speech transcription using faster-whisper.

Supports English and Indian languages (Hindi, Tamil, Telugu, Bengali, etc.)
by setting language=None (autodetect) or specifying a language code.

Usage:
    from speech_to_text import SpeechToText
    stt = SpeechToText()
    result = stt.transcribe("audio.wav")
    print(result["text"])
"""

import os
import logging
from pathlib import Path
from typing import Optional

# faster-whisper wraps CTranslate2 for CPU-efficient Whisper inference
from faster_whisper import WhisperModel

logger = logging.getLogger(__name__)


class SpeechToText:
    """
    Wrapper around faster-whisper for local, offline transcription.

    Model sizes (trade-off between speed and accuracy):
        tiny   — fastest, ~39 MB,  lower accuracy
        base   — good for demos, ~74 MB, decent accuracy
        small  — better quality, ~244 MB
        medium — high quality, ~769 MB (needs ≥8 GB RAM on CPU)

    On CPU set compute_type="int8" for fastest inference.
    On GPU set compute_type="float16".
    """

    def __init__(
        self,
        model_size: str = "base",
        device: str = "cpu",
        compute_type: str = "int8",
        model_dir: Optional[str] = None,
    ):
        """
        Args:
            model_size: Whisper model variant.
            device: "cpu" or "cuda".
            compute_type: Quantisation type — "int8" (CPU) or "float16" (GPU).
            model_dir: Optional local path to cache downloaded model weights.
        """
        self.model_size = model_size
        self.device = device
        self.compute_type = compute_type

        # Resolve model cache directory
        if model_dir is None:
            model_dir = str(Path(__file__).parent.parent / "models" / "whisper")
        os.makedirs(model_dir, exist_ok=True)

        logger.info(
            f"Loading Whisper {model_size} on {device} "
            f"(compute_type={compute_type}, cache={model_dir})"
        )
        # Model is downloaded once and cached locally — fully offline after that
        self.model = WhisperModel(
            model_size,
            device=device,
            compute_type=compute_type,
            download_root=model_dir,
        )
        logger.info("Whisper model loaded successfully.")

    # -------------------------------------------------------------------------
    # Public API
    # -------------------------------------------------------------------------
    def transcribe(
        self,
        audio_path: str,
        language: Optional[str] = None,
        beam_size: int = 5,
        vad_filter: bool = True,
    ) -> dict:
        """
        Transcribe an audio file to text.

        Args:
            audio_path: Path to audio file (WAV, MP3, M4A, etc.).
            language: ISO 639-1 code e.g. "hi" (Hindi), "ta" (Tamil), "te"
                      (Telugu), "en" (English). Pass None for auto-detection.
            beam_size: Beam search width — higher = more accurate but slower.
            vad_filter: Remove silence segments before transcription (recommended).

        Returns:
            {
                "text": full transcript string,
                "language": detected/chosen language code,
                "segments": list of timed segment dicts
            }
        """
        if not os.path.exists(audio_path):
            raise FileNotFoundError(f"Audio file not found: {audio_path}")

        logger.info(f"Transcribing: {audio_path} (language={language})")

        segments, info = self.model.transcribe(
            audio_path,
            language=language,
            beam_size=beam_size,
            vad_filter=vad_filter,
            vad_parameters={"min_silence_duration_ms": 500},
        )

        # Collect all segment texts
        segment_list = []
        full_text_parts = []

        for seg in segments:
            segment_list.append(
                {
                    "start": round(seg.start, 2),
                    "end": round(seg.end, 2),
                    "text": seg.text.strip(),
                }
            )
            full_text_parts.append(seg.text.strip())

        full_text = " ".join(full_text_parts).strip()
        detected_lang = info.language if hasattr(info, "language") else (language or "unknown")

        logger.info(f"Transcript [{detected_lang}]: {full_text}")

        return {
            "text": full_text,
            "language": detected_lang,
            "segments": segment_list,
        }
