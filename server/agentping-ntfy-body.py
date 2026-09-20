#!/usr/bin/env python
# AgentPing publisher + session debounce (DESIGN.md §3.5)
# Called by agent-notify / agent-notify.win with AP_* env vars.
# Modes:
#   (default) gate + publish-or-defer
#   --flush <keyhash> [sleep_sec]  sleep remaining window then publish merged pending
#
# Debounce: finished/waiting share a per-key window (default 180s from first event).
# Metadata (agent/host/state/session/title/tags/priority/task) from the first event;
# subsequent task/detail text is appended into detail. failed publishes immediately
# and clears pending. Key: host\\0session if session set, else host\\0agent.
# AGENTPING_DEBOUNCE_SEC=0 disables debounce.
#
# Pending files live under ${XDG_CACHE_HOME:-~/.cache}/agentping/debounce/<keyhash>/
# (meta.json + parts.jsonl). Credentials are never written to disk; flush reloads conf.

from __future__ import print_function

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import contextmanager

LABELS = {
    "finished": "Finished",
    "failed": "Failed",
    "waiting": "Awaiting approval",
}

PUBLISH_TIMEOUT_SEC = 2.0
DEFAULT_DEBOUNCE_SEC = 180
DETAIL_MAX = 500

try:
    DEVNULL = subprocess.DEVNULL
except AttributeError:  # pragma: no cover - py2 fallback unused
    DEVNULL = open(os.devnull, "wb")


def _eprint(msg):
    print(msg, file=sys.stderr)


def _clip(s, n):
    return (s or "")[:n]


def _debounce_sec():
    raw = os.environ.get("AGENTPING_DEBOUNCE_SEC", "")
    if raw == "":
        return DEFAULT_DEBOUNCE_SEC
    try:
        return max(0, int(raw))
    except ValueError:
        return DEFAULT_DEBOUNCE_SEC


def _cache_root():
    xdg = os.environ.get("XDG_CACHE_HOME", "").strip()
    if xdg:
        base = xdg
    else:
        base = os.path.join(os.path.expanduser("~"), ".cache")
    return os.path.join(base, "agentping", "debounce")


def _key_raw(host, session, agent):
    if session:
        return "%s\0%s" % (host, session)
    return "%s\0%s" % (host, agent)


def _key_hash(host, session, agent):
    return hashlib.sha256(_key_raw(host, session, agent).encode("utf-8")).hexdigest()[:16]


def _pending_dir(keyhash):
    return os.path.join(_cache_root(), keyhash)


@contextmanager
def _dir_lock(path):
    """mkdir-based lock (works on Linux / macOS / Windows without flock)."""
    lock = path + ".lock"
    acquired = False
    for _ in range(100):
        try:
            os.mkdir(lock)
            acquired = True
            break
        except OSError:
            time.sleep(0.05)
    try:
        yield acquired
    finally:
        if acquired:
            try:
                os.rmdir(lock)
            except OSError:
                pass


def _read_json(path, default=None):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _write_json(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    os.replace(tmp, path)


def _append_part(parts_path, task, detail):
    bit_task = _clip(task, 80)
    bit_detail = _clip(detail, 500)
    if not bit_task and not bit_detail:
        return
    with open(parts_path, "a", encoding="utf-8") as f:
        f.write(json.dumps({"task": bit_task, "detail": bit_detail}, ensure_ascii=False))
        f.write("\n")


def _merge_detail(first_detail, parts_path):
    parts = []
    first = (first_detail or "").strip()
    if first:
        parts.append(first)
    try:
        with open(parts_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                detail = (obj.get("detail") or "").strip()
                task = (obj.get("task") or "").strip()
                bit = detail or task
                if bit:
                    parts.append(bit)
    except Exception:
        pass
    merged = "\n".join(parts)
    return merged[:DETAIL_MAX]


def _clear_pending(keyhash):
    d = _pending_dir(keyhash)
    parent = _cache_root()
    try:
        os.makedirs(parent, exist_ok=True)
    except Exception:
        pass
    with _dir_lock(os.path.join(parent, keyhash)):
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)


def _post(url, token, data, topic):
    req = urllib.request.Request(
        url,
        data=data,
        method="POST",
        headers={
            "Authorization": "Bearer %s" % token,
            "Content-Type": "application/json; charset=utf-8",
        },
    )
    with urllib.request.urlopen(req, timeout=PUBLISH_TIMEOUT_SEC) as resp:
        code = getattr(resp, "status", None) or resp.getcode()
        resp.read()
        if int(code) < 200 or int(code) >= 300:
            raise urllib.error.HTTPError(url, int(code), "http=%s" % code, resp.headers, None)


def _publish_fields(fields, tags, prio, url, token, host_topic):
    agent = fields.get("agent", "shell")
    host = fields.get("host", "unknown")
    state = fields.get("state", "finished")
    msg = json.dumps(fields, ensure_ascii=False, separators=(",", ":"))
    title = "[%s] %s %s" % (host, agent, LABELS.get(state, state))
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
                _eprint("agentping-ntfy-body: publish %s failed: %s" % (topic, e))


def _fields_from_env():
    agent = _clip(os.environ.get("AP_AGENT", "shell"), 32)
    host = _clip(os.environ.get("AP_HOST", "unknown"), 64)
    state = _clip(os.environ.get("AP_STATE", "finished"), 16)
    fields = {"v": 1, "agent": agent, "host": host, "state": state}
    task = _clip(os.environ.get("AP_TASK", ""), 80)
    detail = _clip(os.environ.get("AP_DETAIL", ""), 500)
    session = _clip(os.environ.get("AP_SESSION", ""), 64)
    if task:
        fields["task"] = task
    if detail:
        fields["detail"] = detail
    if session:
        fields["session"] = session
    dur = os.environ.get("AP_DUR", "")
    if dur.isdigit():
        fields["dur"] = int(dur)
    tags = [t for t in os.environ.get("AP_TAGS", "").split(",") if t]
    try:
        prio = int(os.environ.get("AP_PRIO", "3") or "3")
    except ValueError:
        prio = 3
    host_topic = os.environ.get("AP_TOPIC_HOST", "agentping-%s" % host)
    return fields, tags, prio, host_topic


def _load_conf_into_env():
    if os.environ.get("AGENTPING_URL") and os.environ.get("AGENTPING_TOKEN"):
        return
    candidates = ["/etc/agentping.conf"]
    home = os.path.expanduser("~")
    if home:
        candidates.append(os.path.join(home, ".agentping.conf"))
    for path in candidates:
        if not os.path.isfile(path):
            continue
        try:
            with open(path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, _, v = line.partition("=")
                    k, v = k.strip(), v.strip().strip('"').strip("'")
                    if k and k not in os.environ:
                        os.environ[k] = v
        except Exception:
            pass
        break


def _resolve_creds():
    _load_conf_into_env()
    url = os.environ.get("AGENTPING_URL", "").rstrip("/")
    token = os.environ.get("AGENTPING_TOKEN", "")
    return url, token


def _spawn_flush(keyhash, sec):
    script = os.path.abspath(__file__)
    env = os.environ.copy()
    try:
        kwargs = {
            "args": [sys.executable, script, "--flush", keyhash, str(int(sec))],
            "env": env,
            "stdin": DEVNULL,
            "stdout": DEVNULL,
            "stderr": DEVNULL,
            "close_fds": True,
        }
        if os.name == "nt":
            # CREATE_NEW_PROCESS_GROUP | DETACHED_PROCESS
            kwargs["creationflags"] = 0x00000200 | 0x00000008
        else:
            kwargs["start_new_session"] = True
        subprocess.Popen(**kwargs)
    except Exception as e:
        _eprint("agentping-ntfy-body: spawn flush failed: %s" % e)


def flush_pending(keyhash, sleep_sec=None):
    if sleep_sec is not None and sleep_sec > 0:
        time.sleep(sleep_sec)

    url, token = _resolve_creds()
    if not url or not token:
        _eprint("agentping-ntfy-body: flush missing url/token")
        return 0

    d = _pending_dir(keyhash)
    parent = _cache_root()
    fields = tags = prio = host_topic = None
    with _dir_lock(os.path.join(parent, keyhash)):
        meta_path = os.path.join(d, "meta.json")
        parts_path = os.path.join(d, "parts.jsonl")
        meta = _read_json(meta_path, None)
        if not meta:
            return 0  # cleared by failed or already flushed
        fields = {
            "v": 1,
            "agent": meta.get("agent", "shell"),
            "host": meta.get("host", "unknown"),
            "state": meta.get("state", "finished"),
        }
        if meta.get("task"):
            fields["task"] = meta["task"]
        if meta.get("session"):
            fields["session"] = meta["session"]
        if meta.get("dur") is not None:
            fields["dur"] = meta["dur"]
        detail = _merge_detail(meta.get("detail") or "", parts_path)
        if detail:
            fields["detail"] = detail
        tags = meta.get("tags") or []
        prio = int(meta.get("prio") or 3)
        host_topic = meta.get("host_topic") or ("agentping-%s" % fields["host"])
        # Drop pending before publish so a concurrent failed can't race a double-send.
        shutil.rmtree(d, ignore_errors=True)

    _publish_fields(fields, tags, prio, url, token, host_topic)
    return 0


def gate_and_publish():
    url, token = _resolve_creds()
    if not url or not token:
        _eprint("agentping-ntfy-body: missing url/token")
        return 0

    fields, tags, prio, host_topic = _fields_from_env()
    state = fields.get("state", "")
    host = fields.get("host", "unknown")
    agent = fields.get("agent", "shell")
    session = fields.get("session", "")
    keyhash = _key_hash(host, session, agent)
    sec = _debounce_sec()

    # failed: always immediate; cancel any pending merge for this key
    if state == "failed":
        _clear_pending(keyhash)
        _publish_fields(fields, tags, prio, url, token, host_topic)
        return 0

    # Debounce disabled → immediate (same as pre-debounce behaviour)
    if sec <= 0:
        _publish_fields(fields, tags, prio, url, token, host_topic)
        return 0

    # finished / waiting → debounce
    parent = _cache_root()
    try:
        os.makedirs(parent, exist_ok=True)
    except Exception as e:
        _eprint("agentping-ntfy-body: cache mkdir failed: %s" % e)
        _publish_fields(fields, tags, prio, url, token, host_topic)
        return 0

    d = _pending_dir(keyhash)
    with _dir_lock(os.path.join(parent, keyhash)):
        meta_path = os.path.join(d, "meta.json")
        parts_path = os.path.join(d, "parts.jsonl")
        meta = _read_json(meta_path, None) if os.path.isdir(d) else None
        now = time.time()

        if meta and (now - float(meta.get("started_at", 0))) < float(meta.get("sec", sec)):
            # Window still open: accumulate task/detail only (do not reset the window)
            _append_part(parts_path, fields.get("task", ""), fields.get("detail", ""))
            return 0

        # New window (or expired leftover): reset and schedule flush
        if os.path.isdir(d):
            shutil.rmtree(d, ignore_errors=True)
        try:
            os.makedirs(d, exist_ok=True)
        except Exception as e:
            _eprint("agentping-ntfy-body: pending mkdir failed: %s" % e)
            _publish_fields(fields, tags, prio, url, token, host_topic)
            return 0

        meta = {
            "agent": fields.get("agent"),
            "host": fields.get("host"),
            "state": fields.get("state"),
            "task": fields.get("task", ""),
            "detail": fields.get("detail", ""),
            "session": fields.get("session", ""),
            "dur": fields.get("dur"),
            "tags": tags,
            "prio": prio,
            "host_topic": host_topic,
            "started_at": now,
            "sec": sec,
        }
        _write_json(meta_path, meta)
        # Ensure sleeper inherits credentials (parent already has them in env)
        os.environ["AGENTPING_URL"] = url
        os.environ["AGENTPING_TOKEN"] = token

    _spawn_flush(keyhash, sec)
    return 0


def main(argv=None):
    argv = list(argv if argv is not None else sys.argv[1:])
    try:
        if argv and argv[0] == "--flush":
            keyhash = argv[1] if len(argv) > 1 else ""
            sleep_sec = int(argv[2]) if len(argv) > 2 else 0
            if not keyhash:
                return 0
            return flush_pending(keyhash, sleep_sec=sleep_sec)
        return gate_and_publish()
    except Exception as e:
        _eprint("agentping-ntfy-body: %s" % e)
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
