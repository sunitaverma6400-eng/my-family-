"""Model Router: key pools, quota groups, cooldowns, fallback chain, circuit breaker, Brain Status.

All three providers expose an OpenAI-compatible chat endpoint, so one adapter serves them all.
Keys come ONLY from environment variables (JSON list per provider) and are never logged.
"""
import hashlib
import json
import logging
import os
import random
import re
import tempfile
import threading
import time
from dataclasses import dataclass

import requests

log = logging.getLogger("router")

PROVIDERS = {
    "groq": {"url": "https://api.groq.com/openai/v1/chat/completions", "env": "GROQ_KEYS"},
    "gemini": {
        "url": "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "env": "GEMINI_KEYS",
    },
    "openrouter": {"url": "https://openrouter.ai/api/v1/chat/completions", "env": "OPENROUTER_KEYS"},
}

AUTH_COOL_S = 30 * 60  # a rejected key is retried every 30 min (one cheap call), not locked out for hours
MODEL_COOL_S = 600
BAD_REQUEST_COOL_S = 120


class _Fail(Exception):
    def __init__(self, kind, status=None, retry_after=None, detail=""):
        super().__init__(f"{kind}:{status}")
        self.kind = kind
        self.status = status
        self.retry_after = retry_after
        self.detail = detail


class AllBusy(Exception):
    def __init__(self, retry_after_s, errors):
        super().__init__("all providers busy")
        self.retry_after_s = retry_after_s
        self.errors = errors


@dataclass
class Result:
    text: str
    provider: str
    model: str
    key_hint: str
    attempts: int
    tier: str
    raw: dict = None  # provider's JSON reply (used to read search sources of Groq Compound)


@dataclass
class KeyState:
    provider: str
    key: str
    group: str
    kid: str
    cooling_until: float = 0.0
    fails: int = 0
    req_today: int = 0
    ok_today: int = 0
    day: str = ""
    last_error: str = ""
    last_used: float = 0.0
    last_ok: float = 0.0

    @property
    def hint(self):
        return "…" + self.key[-4:]


def parse_keys(raw, provider):
    """Accepts '["k1","k2"]', '[{"key":"k1","group":"mail1"}]' or 'k1,k2'. Returns [(key, group)]."""
    if not raw or not raw.strip():
        return []
    raw = raw.strip()
    try:
        data = json.loads(raw)
    except ValueError:
        data = [x.strip() for x in raw.split(",") if x.strip()]
    if isinstance(data, str):
        data = [data]
    out, seen = [], set()
    for i, item in enumerate(data if isinstance(data, list) else []):
        if isinstance(item, str):
            key, group = item, f"{provider}-{i + 1}"
        elif isinstance(item, dict):
            key, group = item.get("key"), item.get("group") or f"{provider}-{i + 1}"
        else:
            continue
        key = (key or "").strip()
        if key and key not in seen:
            seen.add(key)
            out.append((key, str(group)))
    return out


def _parse_retry_after(h):
    v = h.get("retry-after")
    if v is None:
        return None
    try:
        return max(1.0, min(float(v), 3600.0))
    except (TypeError, ValueError):
        return None


def _classify(status, headers, text):
    h = {str(k).lower(): v for k, v in (headers or {}).items()}
    low = (text or "").lower()
    if status == 429:
        return "rate", _parse_retry_after(h)
    if status in (401, 403) or any(x in low for x in ("invalid auth key", "api key not valid", "api_key_invalid", "invalid api key")):
        return "auth", None
    if status == 404 or any(s in low for s in ("decommission", "does not exist", "not found", "no longer supported")):
        return "model", None
    if status in (400, 422):
        return "bad_request", None
    if status == 408 or status >= 500:
        return "server", _parse_retry_after(h)
    return "bad_request", None


_THINK = re.compile(r"<think>.*?</think>", re.S)


def iter_sse(resp):
    """Text pieces from an OpenAI-style server-sent-events response."""
    try:
        resp.encoding = "utf-8"
    except Exception:
        pass
    for line in resp.iter_lines(decode_unicode=True):
        if isinstance(line, bytes):
            line = line.decode("utf-8", "replace")
        if not line or not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            break
        try:
            piece = json.loads(data)["choices"][0].get("delta", {}).get("content")
        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
            continue
        if piece:
            yield piece


def _clean(text):
    return _THINK.sub("", text or "").strip()


def load_config(path=None):
    path = path or os.path.join(os.path.dirname(os.path.abspath(__file__)), "config", "models.json")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


class Router:
    def __init__(self, cfg, env=None, transport=None, clock=None, state_path=None):
        self.cfg = cfg
        self.transport = transport or requests.post
        self.clock = clock or time.time
        self.state_path = state_path
        self.lock = threading.RLock()
        self.model_cool = {}
        self.breaker = {p: {"fails": 0, "open_until": 0.0} for p in PROVIDERS}
        env = os.environ if env is None else env
        self.keys = {}
        for prov, meta in PROVIDERS.items():
            lst = []
            for key, group in parse_keys(env.get(meta["env"], ""), prov):
                kid = hashlib.sha256(key.encode()).hexdigest()[:10]
                lst.append(KeyState(prov, key, group, kid))
            self.keys[prov] = lst
        self._load_state()

    # ---------- helpers ----------
    def now(self):
        return self.clock()

    def _today(self):
        return time.strftime("%Y-%m-%d", time.gmtime(self.now()))

    def _roll_day(self, ks):
        today = self._today()
        if ks.day != today:
            ks.day, ks.req_today, ks.ok_today = today, 0, 0

    def tier_for(self, character):
        return self.cfg.get("characters", {}).get(character, "fast")

    @staticmethod
    def _backoff(fails, base):
        return min(900.0, base * (2 ** max(0, fails - 1))) * random.uniform(0.8, 1.2)

    # ---------- persistence (counters only, never keys) ----------
    def _load_state(self):
        if not self.state_path or not os.path.exists(self.state_path):
            return
        try:
            with open(self.state_path, encoding="utf-8") as f:
                saved = json.load(f)
        except (OSError, ValueError):
            return
        now = self.now()
        for lst in self.keys.values():
            for ks in lst:
                s = saved.get(ks.kid)
                if not s:
                    continue
                ks.day = s.get("day", "")
                ks.req_today = s.get("req_today", 0)
                ks.ok_today = s.get("ok_today", 0)
                ks.fails = s.get("fails", 0)
                cu = s.get("cooling_until", 0.0)
                ks.cooling_until = cu if cu > now else 0.0
                self._roll_day(ks)

    def _save_state(self):
        if not self.state_path:
            return
        data = {}
        for lst in self.keys.values():
            for ks in lst:
                data[ks.kid] = {
                    "day": ks.day, "req_today": ks.req_today, "ok_today": ks.ok_today,
                    "fails": ks.fails, "cooling_until": ks.cooling_until,
                }
        try:
            d = os.path.dirname(os.path.abspath(self.state_path))
            fd, tmp = tempfile.mkstemp(dir=d)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f)
            os.replace(tmp, self.state_path)
        except OSError:
            log.warning("state save failed")

    # ---------- selection ----------
    def _provider_ready(self, prov, model):
        now = self.now()
        if not self.keys.get(prov):
            return False
        if self.breaker[prov]["open_until"] > now:
            return False
        if self.model_cool.get((prov, model), 0) > now:
            return False
        return True

    def _pick_key(self, prov):
        with self.lock:
            now = self.now()
            healthy = [k for k in self.keys.get(prov, []) if k.cooling_until <= now]
            if not healthy:
                return None
            ks = min(healthy, key=lambda k: (k.last_used, k.req_today))
            ks.last_used = now
            self._roll_day(ks)
            ks.req_today += 1
            return ks

    # ---------- outcome handling ----------
    def _on_success(self, ks):
        with self.lock:
            ks.fails = 0
            ks.ok_today += 1
            ks.last_ok = self.now()
            ks.last_error = ""
            self.breaker[ks.provider]["fails"] = 0
            self._save_state()

    def _on_failure(self, ks, model, f):
        with self.lock:
            now = self.now()
            ks.last_error = f"{f.kind}" + (f" {f.status}" if f.status else "")
            br = self.breaker[ks.provider]
            if f.kind == "rate":
                ks.fails += 1
                wait = f.retry_after if f.retry_after else self._backoff(ks.fails, 20)
                for other in self.keys[ks.provider]:
                    if other.group == ks.group:  # same project/org shares one quota
                        other.cooling_until = max(other.cooling_until, now + wait)
            elif f.kind in ("server", "timeout"):
                ks.fails += 1
                wait = f.retry_after if f.retry_after else self._backoff(ks.fails, 10)
                ks.cooling_until = max(ks.cooling_until, now + wait)
            elif f.kind == "auth":
                ks.cooling_until = now + AUTH_COOL_S
            elif f.kind == "model":
                self.model_cool[(ks.provider, model)] = now + MODEL_COOL_S
            elif f.kind == "bad_request":
                self.model_cool[(ks.provider, model)] = now + BAD_REQUEST_COOL_S
            elif f.kind == "empty":
                ks.cooling_until = max(ks.cooling_until, now + 5)
            if f.kind in ("rate", "server", "timeout", "auth"):
                br["fails"] += 1
                if br["fails"] >= self.cfg["breaker"]["threshold"]:
                    br["open_until"] = now + self.cfg["breaker"]["open_s"]
                    br["fails"] = 0
                    log.warning("circuit open for %s", ks.provider)
            self._save_state()
        log.info("fail provider=%s model=%s key=%s kind=%s status=%s", ks.provider, model, ks.kid, f.kind, f.status)

    def _soonest_retry(self):
        now = self.now()
        waits = [k.cooling_until - now for lst in self.keys.values() for k in lst if k.cooling_until > now]
        waits += [b["open_until"] - now for b in self.breaker.values() if b["open_until"] > now]
        return int(max(5, min(120, min(waits)))) if waits else 15

    # ---------- the call ----------
    def _call(self, ks, model, messages, temperature, max_tokens, extra=None):
        req = self.cfg["request"]
        headers = {"Authorization": f"Bearer {ks.key}", "Content-Type": "application/json"}
        if ks.provider == "openrouter":
            headers["X-Title"] = "Stark Family"
        payload = {"model": model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens}
        if extra:
            payload.update(extra)
        try:
            resp = self.transport(PROVIDERS[ks.provider]["url"], headers=headers, json=payload, timeout=req["timeout_s"])
        except requests.Timeout:
            raise _Fail("timeout", detail="timeout")
        except requests.RequestException as e:
            raise _Fail("timeout", detail=type(e).__name__)
        status = resp.status_code
        if status != 200:
            body = (getattr(resp, "text", "") or "")[:300].replace(ks.key, "***")
            kind, ra = _classify(status, resp.headers, body)
            raise _Fail(kind, status, ra, body)
        try:
            data = resp.json()
            text = _clean(data["choices"][0]["message"]["content"])
        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
            raise _Fail("empty", status, detail="bad response shape")
        if not text:
            raise _Fail("empty", status, detail="empty text")
        return text, data

    def healthy_ratio(self):
        """Share of configured keys that are not cooling right now (1.0 when there are no keys at all)."""
        now = self.now()
        allk = [k for lst in self.keys.values() for k in lst]
        if not allk:
            return 1.0
        return sum(1 for k in allk if k.cooling_until <= now) / len(allk)

    def complete(self, tier, messages, temperature=None, max_tokens=None, priority=1):
        """priority: 1 = my direct request, 2 = running task, 3 = research, 4 = family chatter / background.
        When quota is low (many keys cooling) priorities 3 and 4 wait, so 1 and 2 always have room."""
        floor = self.cfg.get("reserve", {})
        if priority >= 3 and self.healthy_ratio() < floor.get("min_healthy_ratio_for_background", 0.5):
            raise AllBusy(self._soonest_retry(), ["reserved_for_direct_requests"])
        if priority >= 2 and self.healthy_ratio() < floor.get("min_healthy_ratio_for_tasks", 0.15):
            raise AllBusy(self._soonest_retry(), ["almost_no_healthy_keys"])
        req = self.cfg["request"]
        temperature = req["temperature"] if temperature is None else temperature
        max_tokens = req["max_tokens"] if max_tokens is None else max_tokens
        chain = self.cfg["tiers"].get(tier) or self.cfg["tiers"]["fast"]
        total, errors = 0, []
        for entry in chain:
            prov, model = entry["provider"], entry["model"]
            if not self._provider_ready(prov, model):
                errors.append(f"{prov}/{model}:{'no_key' if not self.keys.get(prov) else 'cooling/skipped'}")
                continue
            used = 0
            while used < req["max_attempts_per_provider"] and total < req["max_attempts_total"]:
                ks = self._pick_key(prov)
                if ks is None:
                    errors.append(f"{prov}/{model}:no_healthy_key")
                    break
                used += 1
                total += 1
                try:
                    text, raw = self._call(ks, model, messages, temperature, max_tokens, entry.get("params"))
                except _Fail as f:
                    errors.append(f"{prov}/{model}:{f.kind}")
                    self._on_failure(ks, model, f)
                    if f.kind in ("model", "bad_request"):
                        break  # this model is the problem, not the key: next chain entry
                    if not self._provider_ready(prov, model):
                        break
                    continue
                self._on_success(ks)
                log.info("ok tier=%s provider=%s model=%s key=%s attempts=%d", tier, prov, model, ks.kid, total)
                return Result(text, prov, model, ks.hint, total, tier, raw if isinstance(raw, dict) else None)
            if total >= req["max_attempts_total"]:
                break
        raise AllBusy(self._soonest_retry(), errors)

    # ---------- grounded web search (Gemini native API with Google Search tool) ----------
    def search_grounded(self, prompt, max_tokens=1200, priority=3):
        """One web-grounded answer. Returns (text, sources[{title,url}], provider, model). Raises AllBusy.

        Uses the same key pool, cooldowns and circuit breaker as normal calls. Only Gemini offers grounding here;
        callers fall back to a plain page search when this raises AllBusy.
        """
        rc = self.cfg.get("research", {})
        floor = self.cfg.get("reserve", {})
        if priority >= 3 and self.healthy_ratio() < floor.get("min_healthy_ratio_for_background", 0.5):
            raise AllBusy(self._soonest_retry(), ["reserved_for_direct_requests"])
        model = rc.get("model", "gemini-flash-lite-latest")
        timeout = rc.get("timeout_s", 40)
        errors = []
        if not self._provider_ready("gemini", model):
            raise AllBusy(self._soonest_retry(), ["gemini:not_ready"])
        for _ in range(self.cfg["request"]["max_attempts_per_provider"]):
            ks = self._pick_key("gemini")
            if ks is None:
                errors.append("gemini:no_healthy_key (cooling)")
                break
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
            body = {"contents": [{"role": "user", "parts": [{"text": prompt}]}],
                    "tools": [{"google_search": {}}],
                    "generationConfig": {"temperature": 0.2, "maxOutputTokens": max_tokens}}
            try:
                try:
                    resp = self.transport(url, headers={"x-goog-api-key": ks.key, "Content-Type": "application/json"},
                                          json=body, timeout=timeout)
                except requests.Timeout:
                    raise _Fail("timeout", detail="timeout")
                except requests.RequestException as e:
                    raise _Fail("timeout", detail=type(e).__name__)
                if resp.status_code != 200:
                    body = (getattr(resp, "text", "") or "")[:300].replace(ks.key, "***")
                    kind, ra = _classify(resp.status_code, resp.headers, body)
                    raise _Fail(kind, resp.status_code, ra, body)
                try:
                    j = resp.json()
                    cand = j["candidates"][0]
                    text = "".join(p.get("text", "") for p in cand["content"]["parts"]).strip()
                except (ValueError, KeyError, IndexError, TypeError, AttributeError):
                    raise _Fail("empty", resp.status_code, detail="bad response shape")
                if not text:
                    raise _Fail("empty", resp.status_code, detail="empty text")
            except _Fail as f:
                why = re.sub(r"\s+", " ", f.detail or "")[:160]
                errors.append(f"gemini/{model}:{f.kind}:{f.status or '-'}" + (f" {why}" if why else ""))
                log.warning("grounded search failed kind=%s status=%s detail=%s", f.kind, f.status, why)
                self._on_failure(ks, model, f)
                if f.kind in ("model", "bad_request"):
                    break
                if not self._provider_ready("gemini", model):
                    break
                continue
            self._on_success(ks)
            sources, seen = [], set()
            for ch in (cand.get("groundingMetadata", {}) or {}).get("groundingChunks", []) or []:
                web = ch.get("web") or {}
                u = web.get("uri")
                if u and u not in seen:
                    seen.add(u)
                    sources.append({"title": str(web.get("title") or "")[:120], "url": u})
            return _clean(text), sources[:6], "gemini", model
        raise AllBusy(self._soonest_retry(), errors)

    # ---------- embeddings (Gemini batchEmbedContents) ----------
    def embed(self, texts, priority=3):
        """Vectors for short texts (memory retrieval). Returns list of float lists. Raises AllBusy; callers fall back to keywords."""
        ec = self.cfg.get("embedding", {})
        model, dims = ec.get("model", "gemini-embedding-001"), int(ec.get("dims", 256))
        floor = self.cfg.get("reserve", {})
        if priority >= 3 and self.healthy_ratio() < floor.get("min_healthy_ratio_for_background", 0.5):
            raise AllBusy(self._soonest_retry(), ["reserved_for_direct_requests"])
        errors = []
        for _ in range(self.cfg["request"]["max_attempts_per_provider"]):
            ks = self._pick_key("gemini")
            if ks is None:
                errors.append("gemini:no_healthy_key (cooling)")
                break
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:batchEmbedContents"
            body = {"requests": [{"model": f"models/{model}", "content": {"parts": [{"text": t[:2000]}]},
                                  "outputDimensionality": dims, "taskType": "SEMANTIC_SIMILARITY"} for t in texts]}
            try:
                try:
                    resp = self.transport(url, headers={"x-goog-api-key": ks.key, "Content-Type": "application/json"},
                                          json=body, timeout=ec.get("timeout_s", 12))
                except requests.RequestException as e:
                    raise _Fail("timeout", detail=type(e).__name__)
                if resp.status_code != 200:
                    b = (getattr(resp, "text", "") or "")[:300].replace(ks.key, "***")
                    kind, ra = _classify(resp.status_code, resp.headers, b)
                    raise _Fail(kind, resp.status_code, ra, b)
                try:
                    vecs = [[round(float(x), 5) for x in e["values"]] for e in resp.json()["embeddings"]]
                except (ValueError, KeyError, TypeError, AttributeError):
                    raise _Fail("empty", resp.status_code, detail="bad embedding shape")
                if len(vecs) != len(texts):
                    raise _Fail("empty", resp.status_code, detail="count mismatch")
            except _Fail as f:
                errors.append(f"gemini/{model}:{f.kind}:{f.status or '-'}")
                self._on_failure(ks, model, f)
                if f.kind in ("model", "bad_request") or not self._provider_ready("gemini", model):
                    break
                continue
            self._on_success(ks)
            return vecs
        raise AllBusy(self._soonest_retry(), errors)

    # ---------- streaming chat (OpenAI-compatible SSE) ----------
    def _open_stream(self, ks, model, messages, temperature, max_tokens, extra=None):
        req = self.cfg["request"]
        headers = {"Authorization": f"Bearer {ks.key}", "Content-Type": "application/json"}
        if ks.provider == "openrouter":
            headers["X-Title"] = "Stark Family"
        payload = {"model": model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens, "stream": True}
        if extra:
            payload.update(extra)
        try:
            resp = self.transport(PROVIDERS[ks.provider]["url"], headers=headers, json=payload, timeout=req["timeout_s"], stream=True)
        except requests.Timeout:
            raise _Fail("timeout", detail="timeout")
        except requests.RequestException as e:
            raise _Fail("timeout", detail=type(e).__name__)
        if resp.status_code != 200:
            body = (getattr(resp, "text", "") or "")[:300].replace(ks.key, "***")
            kind, ra = _classify(resp.status_code, resp.headers, body)
            raise _Fail(kind, resp.status_code, ra, body)
        return resp

    def stream(self, tier, messages, temperature=None, max_tokens=None, priority=1):
        """Yields ("delta", text) pieces and finally ("done", Result), or ("error", reason) if the stream broke after output began.
        Key rotation and provider fallback happen only BEFORE the first piece; once words are flowing there is no silent switch mid-sentence.
        Raises AllBusy when nothing could start."""
        floor = self.cfg.get("reserve", {})
        if priority >= 3 and self.healthy_ratio() < floor.get("min_healthy_ratio_for_background", 0.5):
            raise AllBusy(self._soonest_retry(), ["reserved_for_direct_requests"])
        if priority >= 2 and self.healthy_ratio() < floor.get("min_healthy_ratio_for_tasks", 0.15):
            raise AllBusy(self._soonest_retry(), ["almost_no_healthy_keys"])
        req = self.cfg["request"]
        temperature = req["temperature"] if temperature is None else temperature
        max_tokens = req["max_tokens"] if max_tokens is None else max_tokens
        chain = self.cfg["tiers"].get(tier) or self.cfg["tiers"]["fast"]
        total, errors = 0, []
        for entry in chain:
            prov, model = entry["provider"], entry["model"]
            if not self._provider_ready(prov, model):
                errors.append(f"{prov}/{model}:{'no_key' if not self.keys.get(prov) else 'cooling/skipped'}")
                continue
            used = 0
            while used < req["max_attempts_per_provider"] and total < req["max_attempts_total"]:
                ks = self._pick_key(prov)
                if ks is None:
                    errors.append(f"{prov}/{model}:no_healthy_key")
                    break
                used += 1
                total += 1
                started, parts = False, []
                try:
                    resp = self._open_stream(ks, model, messages, temperature, max_tokens, entry.get("params"))
                    try:
                        for piece in iter_sse(resp):
                            started = True
                            parts.append(piece)
                            yield ("delta", piece)
                    except requests.RequestException as e:
                        raise _Fail("timeout", detail=type(e).__name__)
                    if not _clean("".join(parts)):
                        raise _Fail("empty", 200, detail="empty stream")
                except _Fail as f:
                    errors.append(f"{prov}/{model}:{f.kind}")
                    self._on_failure(ks, model, f)
                    if started:
                        yield ("error", f"stream_interrupted:{f.kind}")
                        return
                    if f.kind in ("model", "bad_request"):
                        break
                    if not self._provider_ready(prov, model):
                        break
                    continue
                self._on_success(ks)
                log.info("ok stream tier=%s provider=%s model=%s key=%s attempts=%d", tier, prov, model, ks.kid, total)
                yield ("done", Result(_clean("".join(parts)), prov, model, ks.hint, total, tier, None))
                return
            if total >= req["max_attempts_total"]:
                break
        raise AllBusy(self._soonest_retry(), errors)

    # ---------- Brain Status ----------
    def status(self, characters=()):
        now = self.now()
        out = {"time": int(now), "providers": {}, "characters": {}}
        with self.lock:
            for prov, lst in self.keys.items():
                br = self.breaker[prov]
                keys = []
                for k in lst:
                    self._roll_day(k)
                    cooling = k.cooling_until > now
                    keys.append({
                        "key": k.hint, "group": k.group,
                        "state": "cooling" if cooling else "healthy",
                        "cooling_for_s": int(k.cooling_until - now) if cooling else 0,
                        "req_today": k.req_today, "ok_today": k.ok_today,
                        "last_error": k.last_error,
                    })
                out["providers"][prov] = {
                    "configured_keys": len(lst),
                    "breaker": "open" if br["open_until"] > now else "closed",
                    "keys": keys,
                }
            for c in characters:
                tier = self.tier_for(c)
                current = None
                for e in self.cfg["tiers"].get(tier, []):
                    if self._provider_ready(e["provider"], e["model"]) and any(
                        k.cooling_until <= now for k in self.keys[e["provider"]]
                    ):
                        current = f'{e["provider"]}/{e["model"]}'
                        break
                out["characters"][c] = {"tier": tier, "model_now": current}
        return out
