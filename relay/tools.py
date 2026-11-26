"""Phone-tool layer on the relay side: registry, per-character permissions, output parsing, validation.

The model only *proposes* tool calls. Anything not on the character's allow-list, or with invalid
arguments, is dropped here; the Android app checks risk again in its own code before running anything.
"""
import datetime
import json
import os
import re

from memorykit import is_sensitive

BASE = os.path.dirname(os.path.abspath(__file__))
MAX_CALLS = 3


def load_tools(path=None):
    path = path or os.path.join(BASE, "config", "tools.json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


REG = load_tools()


def _int(v, lo, hi):
    if isinstance(v, bool):
        raise ValueError("not an int")
    if isinstance(v, str) and re.fullmatch(r"\s*-?\d+\s*", v):
        v = int(v)
    if isinstance(v, float) and v.is_integer():
        v = int(v)
    if not isinstance(v, int) or not (lo <= v <= hi):
        raise ValueError("int out of range")
    return v


def _str(v, maxlen, required=True):
    if v is None or v == "":
        if required:
            raise ValueError("missing text")
        return ""
    if not isinstance(v, str):
        raise ValueError("not text")
    v = re.sub(r"\s+", " ", v).strip()
    if required and not v:
        raise ValueError("missing text")
    return v[:maxlen]


def _bool(v):
    if isinstance(v, bool):
        return v
    if isinstance(v, str) and v.strip().lower() in ("true", "on", "yes", "1"):
        return True
    if isinstance(v, str) and v.strip().lower() in ("false", "off", "no", "0"):
        return False
    raise ValueError("not a bool")


def _choice(v, options):
    if not isinstance(v, str) or v.strip().lower() not in options:
        raise ValueError("bad choice")
    return v.strip().lower()


def _fact(v):
    s = _str(v, 200)
    if is_sensitive(s):
        raise ValueError("sensitive")
    return s


PERSONA_IDS = ("jarvis", "friday", "edith", "vision", "ultron", "dume", "u", "karen")


def _date(v):
    s = _str(v, 10)
    try:
        datetime.date.fromisoformat(s)
    except ValueError:
        raise ValueError("bad date")
    return s


VALIDATORS = {
    "add_calendar_event": lambda a: {"title": _str(a.get("title"), 80), "date": _date(a.get("date")),
                                     "hour": _int(a.get("hour"), 0, 23), "minute": _int(a.get("minute", 0), 0, 59),
                                     "duration_min": _int(a.get("duration_min", 60), 5, 1440)},
    "set_brightness": lambda a: {"percent": _int(a.get("percent"), 0, 100)},
    "bluetooth_panel": lambda a: {},
    "device_info": lambda a: {"what": _choice(a.get("what", "all"), ("battery", "storage", "network", "all"))},
    "copy_text": lambda a: {"text": _str(a.get("text"), 500)},
    "media_control": lambda a: {"action": _choice(a.get("action"), ("play", "pause", "next", "previous"))},
    "recall": lambda a: {"query": _str(a.get("query"), 100)},
    "usage_stats": lambda a: {"days": _int(a.get("days", 1), 1, 7)},
    "read_notifications": lambda a: {"limit": _int(a.get("limit", 10), 1, 20)},
    "dismiss_notifications": lambda a: {"app": _str(a.get("app"), 40)},
    "send_sms": lambda a: {"contact": _str(a.get("contact"), 60), "text": _str(a.get("text"), 300)},
    "send_whatsapp": lambda a: {"contact": _str(a.get("contact"), 60), "text": _str(a.get("text"), 300)},
    "share_text": lambda a: {"text": _str(a.get("text"), 500)},
    "clear_cache": lambda a: {},
    "edit_rule": lambda a: {"name": _str(a.get("name"), 60), "action": _choice(a.get("action"), ("enable", "disable", "delete"))},
    "camera_look": lambda a: {"mode": _choice(a.get("mode", "ask"), ("ocr", "identify", "ask")), "question": str(a.get("question") or "").strip()[:200]},
    "reply_notification": lambda a: {"app": _str(a.get("app"), 40), "text": _str(a.get("text"), 300)},
    "find_file": lambda a: {"name": _str(a.get("name"), 80)},
    "organise_downloads": lambda a: {},
    "undo_organise": lambda a: {},
    "delete_file": lambda a: {"name": _str(a.get("name"), 120)},
    "restore_file": lambda a: {"name": _str(a.get("name"), 120)},
    "search_in_app": lambda a: {"app": _str(a.get("app"), 40), "query": _str(a.get("query"), 100)},
    "delegate": lambda a: {"to": _choice(a.get("to"), PERSONA_IDS), "task": _str(a.get("task"), 300)},
    "set_dnd": lambda a: {"on": _bool(a.get("on"))},
    "set_volume": lambda a: {"stream": _choice(a.get("stream", "media"), ("media", "ring", "alarm", "notification")),
                             "percent": _int(a.get("percent"), 0, 100)},
    "wifi_panel": lambda a: {},
    "battery_saver": lambda a: {},
    "propose_rule": lambda a: {"teaching": _str(a.get("teaching"), 300)},
    "screen_task": lambda a: {"goal": _str(a.get("goal"), 300)},
    "research": lambda a: {"query": _str(a.get("query"), 200)},
    "ask_family": lambda a: {"question": _str(a.get("question"), 300)},
    "remember_fact": lambda a: {"fact": _fact(a.get("fact")), "importance": _int(a.get("importance", 3), 1, 5)},
    "set_alarm": lambda a: {"hour": _int(a.get("hour"), 0, 23), "minute": _int(a.get("minute", 0), 0, 59),
                            "label": _str(a.get("label"), 40, required=False)},
    "set_timer": lambda a: {"seconds": _int(a.get("seconds"), 1, 86400), "label": _str(a.get("label"), 40, required=False)},
    "set_reminder": lambda a: {"hour": _int(a.get("hour"), 0, 23), "minute": _int(a.get("minute", 0), 0, 59),
                               "text": _str(a.get("text"), 80)},
    "open_app": lambda a: {"app": _str(a.get("app"), 40)},
    "torch": lambda a: {"on": _bool(a.get("on"))},
    "call_contact": lambda a: {"contact": _str(a.get("contact"), 60)},
}


def allowed_for(character, requested, reg=None):
    """Tools this character may use AND the app says it can run."""
    reg = reg or REG
    perm = reg["permissions"].get(character, [])
    if not isinstance(requested, list):
        return []
    req = {str(x) for x in requested}
    return [t for t in perm if t in req and t in reg["tools"] and t in VALIDATORS]


def describe(names, reg=None):
    reg = reg or REG
    return [{"name": n, "desc": reg["tools"][n]["desc"], "args": reg["tools"][n]["args"], "risk": reg["tools"][n]["risk"]}
            for n in names]


def parse_output(text):
    """Model output -> (speech, raw_calls). Falls back to plain text when it is not our JSON shape."""
    t = (text or "").strip()
    t = re.sub(r"^```(?:json)?\s*", "", t, flags=re.I)
    t = re.sub(r"\s*```$", "", t)
    s, e = t.find("{"), t.rfind("}")
    if s != -1 and e > s:
        try:
            obj = json.loads(t[s:e + 1])
        except ValueError:
            obj = None
        if isinstance(obj, dict) and ("speech" in obj or "tool_calls" in obj):
            calls = obj.get("tool_calls")
            sp = obj.get("speech")
            return (sp.strip() if isinstance(sp, str) else ""), (calls if isinstance(calls, list) else [])
    return (text or "").strip(), []


def validate_calls(raw, allowed, reg=None):
    reg = reg or REG
    clean, notes = [], []
    for item in raw[:MAX_CALLS] if isinstance(raw, list) else []:
        name = item.get("name") if isinstance(item, dict) else None
        if name not in allowed:
            notes.append(f"dropped:{name}:not_allowed")
            continue
        args = item.get("args")
        try:
            ok = VALIDATORS[name](args if isinstance(args, dict) else {})
        except ValueError as ex:
            notes.append(f"dropped:{name}:{ex}")
            continue
        risk = reg["tools"][name]["risk"]
        clean.append({"id": f"c{len(clean) + 1}", "name": name, "args": ok, "risk": risk, "confirm": risk != "low"})
    return clean, notes
