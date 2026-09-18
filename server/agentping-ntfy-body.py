import json
import os
import sys
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

LABELS = {
    "finished": "Finished",
    "failed": "Failed",
    "waiting": "Awaiting approval",
}

# Keep well under ZCode hook timeoutMs (20s). Two topics in parallel.
PUBLISH_TIMEOUT_SEC = 2.0


def _post(url: str, token: str, data: bytes, topic: str) -> None:
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
        },
    )
    with urllib.request.urlopen(req, timeout=PUBLISH_TIMEOUT_SEC) as resp:
        resp.read()


def main() -> int:
    url = os.environ.get("AGENTPING_URL", "").rstrip("/")
    token = os.environ.get("AGENTPING_TOKEN", "")
    if not url or not token:
        print("agentping-ntfy-body: missing url/token", file=sys.stderr)
        return 0

    def clip(s: str, n: int) -> str:
        return (s or "")[:n]

    agent = clip(os.environ.get("AP_AGENT", "shell"), 32)
    host = clip(os.environ.get("AP_HOST", "unknown"), 64)
    state = clip(os.environ.get("AP_STATE", "finished"), 16)
    fields = {"v": 1, "agent": agent, "host": host, "state": state}
    for key, env, n in (("task", "AP_TASK", 80), ("detail", "AP_DETAIL", 500), ("session", "AP_SESSION", 64)):
        v = clip(os.environ.get(env, ""), n)
        if v:
            fields[key] = v
    dur = os.environ.get("AP_DUR", "")
    if dur.isdigit():
        fields["dur"] = int(dur)

    msg = json.dumps(fields, ensure_ascii=False, separators=(",", ":"))
    tags = [t for t in os.environ.get("AP_TAGS", "").split(",") if t]
    prio = int(os.environ.get("AP_PRIO", "3") or "3")
    title = f"[{host}] {agent} {LABELS.get(state, state)}"
    host_topic = os.environ.get("AP_TOPIC_HOST", f"agentping-{host}")

    payloads = []
    for topic in (host_topic, "agentping-all"):
        body = {
            "topic": topic,
            "title": title,
            "message": msg,
            "tags": tags,
            "priority": prio,
            "markdown": False,
        }
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        payloads.append((topic, data))

    with ThreadPoolExecutor(max_workers=2) as ex:
        futs = {ex.submit(_post, url, token, data, topic): topic for topic, data in payloads}
        for fut in as_completed(futs):
            topic = futs[fut]
            try:
                fut.result()
            except Exception as e:
                print(f"agentping-ntfy-body: publish {topic} failed: {e}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
