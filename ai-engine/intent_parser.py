"""
intent_parser.py
----------------
Rule-based + optional LLM-backed intent detection and entity extraction.

For hackathon demos without GPU:
  - The default pipeline uses fast regex/keyword matching (zero dependencies).
  - When use_llm=True the LLMInterface is called as a fallback for ambiguous commands.

Supported intents:
    call_contact     → "call Rahul", "Rahul ko call karo"
    set_alarm        → "set alarm for 6 AM", "6 baje alarm lagao"
    send_message     → "send message to Priya"
    send_photo       → "send latest photo to Rahul"
    open_app         → "open YouTube", "YouTube kholo"
    take_photo       → "take a photo"
    set_reminder     → "remind me to take medicine at 8 PM"
    play_music       → "play music"
    unknown          → fallback
"""

import re
import logging
from datetime import datetime
from typing import Optional
from urllib import error, parse, request

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Keyword patterns for each intent
# Each pattern is a compiled regex tested against the lowercased transcript.
# ---------------------------------------------------------------------------
INTENT_PATTERNS: list[tuple[str, list[str]]] = [
    # (intent_name, [regex_patterns ...])
    # NOTE: call_speaker MUST be before call_contact (first-match wins)
    (
        "call_speaker",
        [
            r"\bcall\b.*\b(speaker|speakerphone|loudspeaker|loud)\b",
            r"\b(speaker|speakerphone|loudspeaker)\b.*\bcall\b",
            r"\bcall\b.*\bput\b.*\b(speaker|speakerphone)\b",
            r"\bspeaker\s*(par|pe|mein)\b.*\bcall\b",
            r"\bcall\b.*\bspeaker\s*(par|pe|on)\b",
        ],
    ),
    # NOTE: camera_capture MUST be before take_photo (first-match wins)
    (
        "camera_capture",
        [
            r"\bopen\s+camera\s+and\s+(take|click|capture|shoot)\b",
            r"\b(take|click|capture)\b.*\b(photo|picture|selfie)\b.*\bcamera\b",
            r"\bopen\s+camera.*\bcapture\b",
            r"\bcamera\s+kholke\s+(photo|selfie)\s+(lo|lelo)\b",
            r"\b(photo|selfie)\s+(lo|lelo)\s+camera\s+kholke\b",
        ],
    ),
    (
        "describe_screen",
        [
            r"\bwhat('?s|\s+is)\s+(on\s+)?(my\s+)?screen\b",
            r"\bdescribe\s+(the\s+)?screen\b",
            r"\bsummariz(e|ing)\s+(the\s+)?screen\b",
            r"\bread\s+(the\s+)?screen\b",
            r"\bwhat\s+do\s+(i|you)\s+see\s+on\s+(the\s+)?screen\b",
            r"\bscreen\s+(me\s+)?summary\b",
            r"\bkya\s+hai\s+(screen\s+(par|pe)|screen)\b",
            r"\bscreen\s+(par|pe)\s+kya\s+hai\b",
        ],
    ),
    (
        "ask_time",
        [
            r"\b(what('?s|\s+is)\s+the\s+time|time\s+now|current\s+time)\b",
            r"\btime\s+batao\b",
            r"\bkitne\s+baje\b",
        ],
    ),
    (
        "ask_date",
        [
            r"\b(what('?s|\s+is)\s+(today'?s\s+)?date|today'?s\s+date|date\s+today)\b",
            r"\baaj\s+date\s+kya\s+hai\b",
            r"\btareekh\s+kya\s+hai\b",
        ],
    ),
    (
        "ask_weather",
        [
            r"\b(weather|temperature|forecast)\b",
            r"\bmausam\b",
        ],
    ),
    (
        "ask_basic_info",
        [
            r"\bwho\s+are\s+you\b",
            r"\bwhat\s+can\s+you\s+do\b",
            r"\bhelp\b",
            r"\babout\s+you\b",
        ],
    ),
    (
        "call_contact",
        [
            r"\bcall\b",
            r"\bphone\b",
            r"\bkall\b",
            r"\bcalling\b",
            r"\bको\s*call\b",          # Hindi: "Rahul ko call"
            r"\bcall\s*karo\b",
        ],
    ),
    (
        "set_alarm",
        [
            r"\balarm\b",
            r"\bwake\s*(me\s*)?up\b",
            r"\bset\s*(an?\s*)?alarm\b",
            r"\balarm\s*lagao\b",
            r"\bउठा\s*देना\b",
        ],
    ),
    # NOTE: send_message MUST be before send_photo — first-match wins and
    # 'send a message' must never fall through to the photo patterns.
    (
        "send_message",
        [
            r"\bsend\s*(a\s*)?(message|msg|text)\b",
            r"\b(message|msg|text)\b.*\bsend\b",   # reverse word-order variant
            r"\bwhatsapp\b.*\bsend\b",
            r"\bmessage\s*bhejo\b",
            r"\bsandesh\b",
        ],
    ),
    (
        "send_photo",
        [
            # These patterns only fire when NO message keyword is present
            # (enforced by the parse() disambiguation guard below).
            r"\bsend\b.*\bphoto\b",
            r"\bsend\b.*\b(picture|pic|image)\b",
            r"\bshare\b.*\bphoto\b",
            r"\bshare\b.*\b(picture|pic|image)\b",
            r"\bphoto\b.*\bsend\b",
            r"\b(picture|pic|image)\b.*\bsend\b",
            r"\bphoto\b.*\bभेज\b",
            r"\b(picture|pic|image)\b.*\bभेज\b",
            r"\blatest\s*photo\b",
            r"\blatest\s*(picture|pic|image)\b",
            r"\blast\s*photo\b",
            r"\blast\s*(picture|pic|image)\b",
        ],
    ),
    (
        "open_app",
        [
            r"\bopen\b",
            r"\blaunch\b",
            r"\bstart\b.*\bapp\b",
            r"\bkholo\b",
            r"\bkhol\b",
            r"\bopen\s*karo\b",
        ],
    ),
    (
        "take_photo",
        [
            r"\btake\s*(a\s*)?photo\b",
            r"\btake\s*(a\s*)?(picture|pic|image)\b",
            r"\bcapture\b",
            r"\bclick\s*(a\s*)?photo\b",
            r"\bclick\s*(a\s*)?(picture|pic|image)\b",
            r"\bphoto\s*(lo|lelo)\b",
            r"\bselfie\b",
        ],
    ),
    (
        "set_reminder",
        [
            r"\bremind\b",
            r"\breminder\b",
            r"\byaad\s*dilao\b",
            r"\byaad\s*kara\b",
        ],
    ),
    (
        "play_music",
        [
            r"\bplay\s*(some\s*)?music\b",
            r"\bplay\b.*\bsong\b",
            r"\bgaana\s*(bajao|chalo)\b",
            r"\bmusic\s*start\b",
        ],
    ),
    (
        "flashlight_on",
        [
            r"\bturn\s*on\b.*\b(flashlight|torch|light)\b",
            r"\b(flashlight|torch)\s*on\b",
            r"\bon\b.*\b(flashlight|torch)\b",
            r"\bswitch\s*on\b.*\b(flashlight|torch)\b",
            r"\btorch\s*jala\b",
            r"\blight\s*on\b",
        ],
    ),
    (
        "flashlight_off",
        [
            r"\bturn\s*off\b.*\b(flashlight|torch|light)\b",
            r"\b(flashlight|torch)\s*off\b",
            r"\boff\b.*\b(flashlight|torch)\b",
            r"\bswitch\s*off\b.*\b(flashlight|torch)\b",
            r"\btorch\s*band\b",
            r"\blight\s*off\b",
        ],
    ),
]


# ---------------------------------------------------------------------------
# Entity extraction helpers
# ---------------------------------------------------------------------------

# Words that terminate a contact name — anything at or after these is not the name.
_CONTACT_STOP_WORDS = {
    "saying", "say", "tell", "telling", "told", "about", "with",
    "that", "this", "hi", "hello", "hey", "bye", "and", "or",
    "write", "type", "ask", "asking",
}


def _extract_contact(text: str) -> Optional[str]:
    """
    Extract a contact name from patterns like:
      "call Rahul", "send photo to Priya", "message to Amit Singh"
    """
    patterns = [
        # "to <Name>" at or near end of sentence — highest priority
        # \b ensures we match the standalone word "to", not "photo" etc.
        r"\b(?:to|ko)\b\s+([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)\s*$",
        r"\b(?:to|ko)\b\s+([a-z][a-z]+(?:\s+[a-z]+)?)\s*$",
        # General "call/to/for <Name>"
        r"\b(?:call|to|for|ko)\b\s+([A-Z][a-zA-Z]+(?:\s+[A-Z][a-zA-Z]+)?)",
        r"\b(?:call|to|for|ko)\b\s+([a-z][a-z]+(?:\s+[a-z]+)?)",
    ]
    for p in patterns:
        m = re.search(p, text, re.IGNORECASE)
        if m:
            # Trim at the first stop-word so "Priya saying hi" → "Priya"
            raw_words = m.group(1).strip().split()
            clean_words = []
            for w in raw_words:
                if w.lower() in _CONTACT_STOP_WORDS:
                    break
                clean_words.append(w)
            name = " ".join(clean_words).title()
            if not name:
                continue
            # Filter out common false positives
            if name.lower() not in {
                "me", "my", "the", "a", "an", "is", "i", "and", "to", "ko",
                "send", "photo", "latest", "now",
                "message", "msg", "text", "sandesh",
                "picture", "pic", "image",
            }:
                return name
    return None


def _extract_message_body(text: str, contact: Optional[str] = None) -> Optional[str]:
    """
    Extract message body from phrases like:
      "send a message to Priya saying hi"
      "tell him I'll be late"
      "message Priya that I'm on my way"

    Also strips Whisper artifacts:
      - trailing punctuation added by the STT model  (e.g. "hi?")
      - echoed address fragments Whisper appends      (e.g. "hi to Priya?")
    """
    patterns = [
        r"\bsaying\b\s+(.+)$",
        r"\bsay\b\s+(.+)$",
        r"\btell\s+(?:them|him|her|[A-Za-z]+)?\s+(.+)$",
        r"\bmessage\b.+?\bthat\b\s+(.+)$",
        r"\bwrite\b\s+(.+)$",
        r"\btype\b\s+(.+)$",
    ]
    for p in patterns:
        m = re.search(p, text, re.IGNORECASE)
        if m:
            body = m.group(1).strip().strip('"\'')

            # 1. Remove echoed address Whisper appends: "hi to Priya?" → "hi"
            #    Generic: strip trailing "to <word(s)>" up to end of string
            body = re.sub(r"\s+to\s+[A-Za-z]+(\s+[A-Za-z]+)?\s*[?!.]*$", "", body,
                          flags=re.IGNORECASE).strip()
            # Also strip if we know the contact name specifically
            if contact:
                escaped = re.escape(contact)
                body = re.sub(
                    r"\s+to\s+" + escaped + r"\s*[?!.]*$", "", body,
                    flags=re.IGNORECASE
                ).strip()

            # 2. Strip trailing punctuation Whisper adds (?, !, ., ;)
            body = body.rstrip("?!.,;:")

            if body:
                return body
    return None


def _extract_time(text: str) -> Optional[str]:
    """
    Extract time strings like "6 AM", "06:30", "18:00", "6 baje".
    """
    patterns = [
        r"\b(\d{1,2}:\d{2})\s*(am|pm)?\b",   # 06:30 or 06:30 AM
        r"\b(\d{1,2})\s*(am|pm)\b",            # 6 AM
        r"\b(\d{1,2})\s*baje\b",               # 6 baje (Hindi)
    ]
    for p in patterns:
        m = re.search(p, text, re.IGNORECASE)
        if m:
            return m.group(0).strip()
    return None


def _extract_weather_location(text: str) -> Optional[str]:
    """
    Extract a location from weather queries like:
      "what's the weather in Delhi"
      "temperature in Mumbai"
    """
    m = re.search(r"\bin\s+([a-zA-Z]+(?:\s+[a-zA-Z]+)?)\b", text, re.IGNORECASE)
    if m:
        return m.group(1).strip()
    return None


def _fetch_weather(location: Optional[str]) -> str:
    """
    Lightweight weather lookup via wttr.in (no API key required).
    Returns a short human-friendly sentence.
    """
    try:
        loc = (location or "").strip()
        path = parse.quote(loc) if loc else ""
        url = f"https://wttr.in/{path}?format=3"
        with request.urlopen(url, timeout=4) as resp:
            text = resp.read().decode("utf-8", errors="ignore").strip()
        if text:
            # Example: "Delhi: +31°C"
            return f"Current weather: {text}."
    except (error.URLError, TimeoutError, ValueError):
        pass
    if location:
        return f"I could not fetch live weather for {location} right now."
    return "I could not fetch live weather right now."


def _build_basic_answer(intent: str, text: str) -> str:
    now = datetime.now()
    if intent == "ask_time":
        return f"The time is {now.strftime('%I:%M %p').lstrip('0')}."
    if intent == "ask_date":
        return f"Today is {now.strftime('%A, %d %B %Y')}."
    if intent == "ask_weather":
        location = _extract_weather_location(text)
        return _fetch_weather(location)
    if intent == "ask_basic_info":
        return (
            "I am your AI Android Assistant. I can help with calling contacts, "
            "sending messages or photos, opening apps, setting alarms, and answering "
            "basic questions like time, date, and weather."
        )
    return ""


def _extract_app_name(text: str) -> Optional[str]:
    """
    Extract an app name from "open YouTube", "launch Chrome", etc.
    Maps common names to their Android package names.
    """
    known_apps: dict[str, str] = {
        "youtube": "com.google.android.youtube",
        "whatsapp": "com.whatsapp",
        "instagram": "com.instagram.android",
        "maps": "com.google.android.apps.maps",
        "google maps": "com.google.android.apps.maps",
        "chrome": "com.android.chrome",
        "camera": "com.android.camera2",
        "spotify": "com.spotify.music",
        "netflix": "com.netflix.mediaclient",
        "twitter": "com.twitter.android",
        "x": "com.twitter.android",
        "facebook": "com.facebook.katana",
        "telegram": "org.telegram.messenger",
        "gmail": "com.google.android.gm",
        "settings": "com.android.settings",
        "calculator": "com.google.android.calculator",
        "clock": "com.google.android.deskclock",
    }

    # Look for "open <AppName>" / "launch <AppName>" (English)
    # Also handle Hindi word order: "<AppName> kholo" (app first)
    m = re.search(r"(?:open|launch|start|kholo|khol)\s+([a-zA-Z\s]+)", text, re.IGNORECASE)
    if not m:
        # Hindi: "YouTube kholo" — app name precedes the verb
        m = re.search(r"([a-zA-Z]+)\s+(?:kholo|khol|chalao|chalav)", text, re.IGNORECASE)
    if m:
        raw = m.group(1).strip().lower()
        # Check exact match first, then partial
        if raw in known_apps:
            return known_apps[raw]
        for key, pkg in known_apps.items():
            if key in raw or raw in key:
                return pkg
        # Return capitalised name as-is if no mapping found
        return raw.title()
    return None


# ---------------------------------------------------------------------------
# Main intent parser
# ---------------------------------------------------------------------------

class IntentParser:
    """
    Lightweight rule-based intent parser.
    Falls back to LLMInterface when use_llm=True and rules return 'unknown'.
    """

    def __init__(self, llm_interface=None):
        """
        Args:
            llm_interface: Optional LLMInterface instance for LLM-backed fallback.
        """
        self.llm = llm_interface

    def parse(self, text: str) -> dict:
        """
        Parse a transcript string and return a structured intent dict.

        Returns:
            {
                "intent":   str,            # e.g. "call_contact"
                "contact":  str | "",       # extracted contact name
                "app":      str | "",       # package name or app name
                "time":     str | "",       # time string
                "raw_text": str,            # original transcript
                "source":   "rules"|"llm"  # which parser was used
            }
        """
        text_lower = text.lower().strip()
        contact = _extract_contact(text) or ""
        result = {
            "intent": "unknown",
            "contact": contact,
            "app": _extract_app_name(text) or "",
            "time": _extract_time(text) or "",
            "message_body": _extract_message_body(text, contact) or "",
            "answer_text": "",
            "raw_text": text,
            "source": "rules",
        }

        # --- Disambiguation guard -------------------------------------------------
        # Media-send guard should run before message guard so phrases like
        # "send the latest picture to Rahul" are never mis-routed to send_message.
        _MEDIA_KEYWORDS = re.compile(r"\b(photo|picture|pic|image|selfie)\b")
        _SEND_SHARE = re.compile(r"\b(send|share|bhejo)\b")
        if _MEDIA_KEYWORDS.search(text_lower) and _SEND_SHARE.search(text_lower):
            result["intent"] = "send_photo"
            logger.debug("Intent forced to send_photo by media-send guard")
            return result

        # --- Disambiguation guard -------------------------------------------------
        # If the transcript contains an explicit message keyword AND a send/share
        # verb, always treat it as send_message regardless of pattern ordering.
        # This catches Whisper mis-transcriptions like "send a photo" when the
        # user clearly said "send a message", and phrases like "send photo message".
        _MSG_KEYWORDS = re.compile(r"\b(message|msg|text|sandesh)\b")
        _SEND_VERBS   = re.compile(r"\b(send|share|bhejo)\b")
        if _MSG_KEYWORDS.search(text_lower) and _SEND_VERBS.search(text_lower):
            result["intent"] = "send_message"
            logger.debug("Intent forced to send_message by disambiguation guard")
            return result

        # Rule-based matching: first match wins
        for intent_name, patterns in INTENT_PATTERNS:
            for pattern in patterns:
                if re.search(pattern, text_lower):
                    result["intent"] = intent_name
                    if intent_name in {"ask_time", "ask_date", "ask_weather", "ask_basic_info"}:
                        result["answer_text"] = _build_basic_answer(intent_name, text)
                    logger.debug(f"Intent matched by rule '{pattern}': {intent_name}")
                    return result

        # No rule matched — try LLM fallback
        if self.llm is not None:
            logger.info("No rule match. Falling back to LLM intent parser.")
            llm_result = self.llm.parse_intent(text)
            if llm_result:
                result.update(llm_result)
                result["source"] = "llm"
            return result

        logger.warning(f"Unknown intent for: '{text}'")
        return result
