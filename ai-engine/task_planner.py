"""
task_planner.py
---------------
Converts a parsed intent dict into an ordered list of concrete execution steps.

Each step is a string that maps directly to a TaskExecutor method on the
Android side (e.g. "get_latest_photo", "open_whatsapp", "send_to_contact").

Design principle:
  - Step names are snake_case strings understood by both Python and Kotlin code.
  - The planner is pure Python (no ML inference), keeping latency near zero.
  - Multi-step commands are fully expanded here so the Android app just iterates.

Example:
    intent: "send_photo",  contact: "Rahul"
    → steps: ["get_latest_photo", "open_whatsapp", "send_to_contact"]
"""

import logging
from typing import Optional

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Step templates keyed by intent
# Values are either a static list or a callable(intent_dict) → list[str]
# ---------------------------------------------------------------------------

def _steps_call_contact(d: dict) -> list[str]:
    return ["call_contact"]


def _steps_set_alarm(d: dict) -> list[str]:
    return ["set_alarm"]


def _steps_send_photo(d: dict) -> list[str]:
    # Do NOT include open_whatsapp — it opens WhatsApp home which causes
    # the subsequent ACTION_SEND jid extra to be ignored.
    return ["get_latest_photo", "send_to_contact"]


def _steps_send_message(d: dict) -> list[str]:
    # Single dedicated step so the executor can use WhatsApp deep-link + pre-filled text.
    # Intentionally separate from send_photo's "send_to_contact" step.
    return ["send_whatsapp_message"]


def _steps_open_app(d: dict) -> list[str]:
    return ["open_app"]


def _steps_take_photo(d: dict) -> list[str]:
    return ["open_camera"]   # Uses system camera intent, not a specific package


def _steps_set_reminder(d: dict) -> list[str]:
    return ["set_alarm"]   # Android AlarmClock handles reminders too


def _steps_play_music(d: dict) -> list[str]:
    return ["open_app"]   # Opens Spotify / YouTube Music


def _steps_flashlight_on(d: dict) -> list[str]:
    return ["flashlight_on"]


def _steps_flashlight_off(d: dict) -> list[str]:
    return ["flashlight_off"]


def _steps_call_speaker(d: dict) -> list[str]:
    # Open call first, then enable speakerphone after the delay in executeSteps
    return ["call_contact", "enable_speaker"]


def _steps_camera_capture(d: dict) -> list[str]:
    # Open camera first, then trigger shutter via AccessibilityService
    return ["open_camera", "capture_photo"]


def _steps_describe_screen(d: dict) -> list[str]:
    return ["describe_screen"]


def _steps_answer_query(d: dict) -> list[str]:
    return ["speak_answer"]


STEP_REGISTRY: dict[str, callable] = {
    "ask_time":       _steps_answer_query,
    "ask_date":       _steps_answer_query,
    "ask_weather":    _steps_answer_query,
    "ask_basic_info": _steps_answer_query,
    "call_contact":   _steps_call_contact,
    "set_alarm":      _steps_set_alarm,
    "send_photo":     _steps_send_photo,
    "send_message":   _steps_send_message,
    "open_app":       _steps_open_app,
    "take_photo":     _steps_take_photo,
    "set_reminder":   _steps_set_reminder,
    "play_music":     _steps_play_music,
    "flashlight_on":   _steps_flashlight_on,
    "flashlight_off":  _steps_flashlight_off,
    "call_speaker":    _steps_call_speaker,
    "camera_capture":  _steps_camera_capture,
    "describe_screen": _steps_describe_screen,
}

# Default app package to use for open_app when not specified
DEFAULT_APP_FOR_INTENT: dict[str, str] = {
    "play_music":  "com.spotify.music",
}


class TaskPlanner:
    """
    Stateless planner: takes an intent dict, returns an enriched command dict
    with a 'steps' list ready for the Android executor.
    """

    def plan(self, intent_dict: dict) -> dict:
        """
        Build the final command payload to send to the Android app.

        Args:
            intent_dict: Output from IntentParser.parse().

        Returns:
            {
                "intent":   str,
                "contact":  str,
                "app":      str,
                "time":     str,
                "steps":    list[str],
                "raw_text": str
            }
        """
        intent = intent_dict.get("intent", "unknown")
        result = dict(intent_dict)   # Copy all parsed entities

        # Resolve default app if missing
        if not result.get("app") and intent in DEFAULT_APP_FOR_INTENT:
            result["app"] = DEFAULT_APP_FOR_INTENT[intent]

        # Lookup step builder
        step_builder = STEP_REGISTRY.get(intent)
        if step_builder:
            steps = step_builder(result)
        else:
            logger.warning(f"No step plan for intent '{intent}'. Using empty steps.")
            steps = []

        result["steps"] = steps
        logger.info(f"Plan for '{intent}': {steps}")
        return result
