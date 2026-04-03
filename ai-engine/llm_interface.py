"""
llm_interface.py
----------------
Minimal LLM adapter used by the engine for:
  - Intent parsing fallback (parse_intent)
  - Screen summarization (summarize_screen)

This implementation targets local Ollama by default and gracefully falls back
to safe defaults when the model endpoint is unavailable.
"""

from __future__ import annotations

import json
import logging
from urllib import error, request


logger = logging.getLogger(__name__)


class LLMInterface:
	def __init__(self, mode: str = "ollama", model_name: str = "llama3.2:3b"):
		self.mode = (mode or "ollama").lower()
		self.model_name = model_name or "llama3.2:3b"
		self.ollama_url = "http://localhost:11434/api/chat"

	def parse_intent(self, text: str) -> dict:
		"""
		Ask the LLM to return intent JSON when rule-based parsing fails.
		Must return keys: intent, contact, app, time, message_body.
		"""
		if not text.strip():
			return self._unknown(text)

		prompt = (
			"You are an intent parser for an Android voice assistant.\n"
			"Return ONLY strict JSON with keys: intent, contact, app, time, message_body.\n"
			"Valid intents: call_contact, call_speaker, set_alarm, send_photo, send_message, "
			"open_app, take_photo, camera_capture, describe_screen, set_reminder, play_music, "
			"flashlight_on, flashlight_off, unknown.\n"
			"If unsure, intent must be unknown. Use empty string for missing fields.\n"
			f"Input: {text}"
		)

		content = self._chat(prompt)
		if not content:
			return self._unknown(text)

		parsed = self._extract_json(content)
		if not parsed:
			return self._unknown(text)

		return {
			"intent": str(parsed.get("intent", "unknown") or "unknown"),
			"contact": str(parsed.get("contact", "") or ""),
			"app": str(parsed.get("app", "") or ""),
			"time": str(parsed.get("time", "") or ""),
			"message_body": str(parsed.get("message_body", "") or ""),
			"raw_text": text,
		}

	def summarize_screen(self, screen_text: str) -> str:
		"""Summarize screen text for accessibility feedback."""
		if not screen_text.strip():
			return "The screen appears empty."

		prompt = (
			"Summarize this Android screen in 1-2 concise sentences. "
			"Mention app/context and key actionable elements.\n\n"
			f"Screen text:\n{screen_text}"
		)

		content = self._chat(prompt)
		if content:
			return content.strip()

		return "I could not summarize the screen right now."

	def _chat(self, prompt: str) -> str:
		if self.mode != "ollama":
			logger.warning("Unsupported LLM mode '%s'. Falling back to empty response.", self.mode)
			return ""

		payload = {
			"model": self.model_name,
			"messages": [{"role": "user", "content": prompt}],
			"stream": False,
		}

		req = request.Request(
			self.ollama_url,
			data=json.dumps(payload).encode("utf-8"),
			headers={"Content-Type": "application/json"},
			method="POST",
		)

		try:
			with request.urlopen(req, timeout=30) as resp:
				data = json.loads(resp.read().decode("utf-8"))
			return str(data.get("message", {}).get("content", "") or "").strip()
		except (error.URLError, error.HTTPError, TimeoutError, json.JSONDecodeError) as exc:
			logger.warning("LLM request failed: %s", exc)
			return ""

	def _extract_json(self, content: str) -> dict | None:
		"""Parse JSON object from raw or markdown-fenced model output."""
		text = content.strip()

		try:
			obj = json.loads(text)
			return obj if isinstance(obj, dict) else None
		except json.JSONDecodeError:
			pass

		start = text.find("{")
		end = text.rfind("}")
		if start == -1 or end == -1 or end <= start:
			return None

		try:
			obj = json.loads(text[start : end + 1])
			return obj if isinstance(obj, dict) else None
		except json.JSONDecodeError:
			return None

	@staticmethod
	def _unknown(text: str) -> dict:
		return {
			"intent": "unknown",
			"contact": "",
			"app": "",
			"time": "",
			"message_body": "",
			"raw_text": text,
		}
