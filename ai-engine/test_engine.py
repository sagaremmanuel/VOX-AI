"""
test_engine.py
--------------
Quick integration test for the AI engine — no Android device required.

Run after starting the server:
    uvicorn main:app --host 0.0.0.0 --port 8000

Then:
    python test_engine.py
"""

import json
import sys
import requests

BASE_URL = "http://localhost:8000"


def test_health():
    print("=== Health check ===")
    r = requests.get(f"{BASE_URL}/health")
    print(json.dumps(r.json(), indent=2))
    assert r.status_code == 200, "Health check failed!"
    print("PASS\n")


def test_parse_command(text: str, expected_intent: str):
    print(f"=== Parse: '{text}' ===")
    r = requests.post(f"{BASE_URL}/parse_command", json={"text": text})
    data = r.json()
    print(json.dumps(data, indent=2))
    actual = data.get("intent")
    status = "PASS" if actual == expected_intent else f"FAIL (expected {expected_intent}, got {actual})"
    print(status + "\n")
    return actual == expected_intent


def test_speech_to_text(audio_path: str):
    print(f"=== STT: {audio_path} ===")
    try:
        with open(audio_path, "rb") as f:
            r = requests.post(
                f"{BASE_URL}/speech_to_text",
                files={"audio": (audio_path, f, "audio/wav")},
            )
        data = r.json()
        print(json.dumps(data, indent=2))
        print("PASS\n")
    except FileNotFoundError:
        print(f"SKIP — file not found: {audio_path}\n")


if __name__ == "__main__":
    all_pass = True

    # 1. Health check
    test_health()

    # 2. Parse command tests
    cases = [
        ("Call Rahul",                              "call_contact"),
        ("Set alarm for 6 AM",                     "set_alarm"),
        ("Open YouTube",                            "open_app"),
        ("Take the latest photo and send to Rahul","send_photo"),
        ("Send message to Priya",                   "send_message"),
        ("Remind me at 8 PM",                       "set_reminder"),
        # Hindi examples
        ("Rahul ko call karo",                      "call_contact"),
        ("6 baje alarm lagao",                      "set_alarm"),
        ("YouTube kholo",                           "open_app"),
    ]

    for text, expected in cases:
        ok = test_parse_command(text, expected)
        if not ok:
            all_pass = False

    # 3. STT test (requires a WAV file)
    test_speech_to_text("test_audio.wav")

    print("=" * 40)
    print("All tests PASSED!" if all_pass else "Some tests FAILED — check output above.")
    sys.exit(0 if all_pass else 1)
