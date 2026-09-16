import json
import sys

try:
    sys.stdout.reconfigure(newline="\n")
except Exception:
    pass

raw = sys.stdin.read()
if not raw.strip():
    raise SystemExit(0)

try:
    data = json.loads(raw)
except Exception:
    raise SystemExit(0)


def g(*keys, default=""):
    for key in keys:
        value = data.get(key)
        if value is None:
            continue
        if isinstance(value, (dict, list)):
            try:
                return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
            except Exception:
                return str(value)
        text = str(value).strip()
        if text:
            return text
    return default


values = [
    g("hook_event_name", "hookEventName"),
    g("session_id", "sessionId"),
    g("prompt"),
    # Prefer short preview; cap long Stop summaries so the hook stays fast.
    g("responsePreview", "last_assistant_message", "responseText")[:500],
    g("tool_name", "toolName"),
    g("tool_input", "toolInput")[:500],
    g("reason")[:200],
]

for item in values:
    sys.stdout.write(item.replace("\r", " ").replace("\n", " ") + "\n")
