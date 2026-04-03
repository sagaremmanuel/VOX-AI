# Models Directory

## Whisper (Speech-to-Text)

The `faster-whisper` library downloads Whisper model weights automatically
on first run and caches them here.

To pre-download the model offline (useful for demos with no internet):

```bash
cd ai-engine
python - <<'EOF'
from faster_whisper import WhisperModel
m = WhisperModel("base", device="cpu", download_root="../models/whisper")
print("Downloaded.")
EOF
```

Available model sizes:

| Model  | Parameters | Disk  | Speed (CPU) | Accuracy |
|--------|-----------|-------|-------------|----------|
| tiny   | 39 M      | 74 MB | Fastest     | Low      |
| base   | 74 M      | 142 MB| Fast        | OK       |
| small  | 244 M     | 466 MB| Medium      | Good     |
| medium | 769 M     | 1.5 GB| Slow        | High     |

**Recommended for hackathon demo:** `base` (good speed + accuracy balance)

---

## Indic ASR (optional)

For better accuracy on Indian languages, place your Indic ASR ONNX model
files inside `indic_asr/`.

Options:
- [AI4Bharat IndicASR](https://github.com/AI4Bharat/IndicSTR)  
- Whisper with `language="hi"` (Hindi) or `language="ta"` (Tamil) also works well.

Whisper natively supports:
- Hindi (`hi`)
- Tamil (`ta`)
- Telugu (`te`)
- Bengali (`bn`)
- Marathi (`mr`)
- Gujarati (`gu`)
- Kannada (`kn`)
- Malayalam (`ml`)
- Punjabi (`pa`)
- Urdu (`ur`)
