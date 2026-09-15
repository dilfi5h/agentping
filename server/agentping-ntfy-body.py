import json
import os
import sys
import urllib.request

LABELS = {
    "finished": "已完成",
    "failed": "失败",
    "waiting": "等待批准",
}


def main() -> int:
    url = os.environ.get("AGENTPING_URL", "").rstrip("/")
    token = os.environ.get("AGENTPING_TOKEN", "")
    if not url or not token:
        print("agentping-ntfy-body: missing url/token", file=sys.stderr)
        return 0

    agent = os.environ.get("AP_AGENT", "shell")
    host = os.environ.get("AP_HOST", "unknown")
    state = os.environ.get("AP_STATE", "finished")
    fields = {"v": 1, "agent": agent, "host": host, "state": state}
    for key, env in (("task", "AP_TASK"), ("detail", "AP_DETAIL"), ("session", "AP_SESSION")):
        v = os.environ.get(env, "")
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
        req = urllib.request.Request(
            url,
            data=data,
            method="POST",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json; charset=utf-8",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                resp.read()
        except Exception as e:
            print(f"agentping-ntfy-body: publish {topic} failed: {e}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
