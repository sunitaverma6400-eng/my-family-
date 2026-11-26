"""Stark Family relay (Flask). Holds all API keys; the Android app never sees them.

Endpoints: GET /health, GET /test (open pages); GET /characters, /brain_status, /diag, POST /chat (auth: X-App-Token)
Run with ONE gunicorn worker so key cooldown state is shared (see render.yaml).
"""
import hmac
import json
import logging
import os
import time

from flask import Flask, Response, jsonify, request, stream_with_context

from prompting import (build_messages, clean_history, language_matches, language_ok, load_personas, norm_lang,
                       rewrite_request, strip_emoji)
import requests

import agent as agentmod
from stream_speech import SpeechStream
import family as familymod
import research as researchmod
import rules as rulesmod
import memorykit
import tools as toolmod
from router import PROVIDERS, AllBusy, Router, load_config
from test_page import PAGE

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("relay")

BASE = os.path.dirname(os.path.abspath(__file__))
VERSION = "m10i"  # shown at /health: confirms which relay code is actually running


def create_app(router=None, personas=None, token=None):
    app = Flask(__name__)
    app.json.ensure_ascii = False  # keep Devanagari readable in responses and logs
    personas = personas or load_personas()
    router = router or Router(load_config(), state_path=os.environ.get("STATE_PATH", os.path.join(BASE, "state.json")))
    app_token = token if token is not None else os.environ.get("APP_TOKEN", "")

    def guard():
        if not app_token:
            return jsonify(error="server_not_configured", detail="APP_TOKEN missing on relay"), 503
        given = request.headers.get("X-App-Token", "")
        if not hmac.compare_digest(given.encode(), app_token.encode()):
            return jsonify(error="unauthorized"), 401
        return None

    @app.get("/health")
    def health():
        return jsonify(ok=True, version=VERSION, time=int(time.time()), characters=list(personas))

    @app.get("/test")
    def test_page():
        return PAGE, 200, {"Content-Type": "text/html; charset=utf-8"}

    @app.get("/diag")
    def diag():
        """Ask each provider which models its first key can use; compare with models.json."""
        if (r := guard()) is not None:
            return r
        urls = {"groq": "https://api.groq.com/openai/v1/models",
                "gemini": "https://generativelanguage.googleapis.com/v1beta/openai/models",
                "openrouter": "https://openrouter.ai/api/v1/models"}
        wanted = {}
        for tier, chain in router.cfg["tiers"].items():
            for e in chain:
                wanted.setdefault(e["provider"], set()).add(e["model"])
        out = {}
        for prov, lst in router.keys.items():
            info = {"keys_configured": len(lst)}
            if not lst:
                info["problem"] = "इस provider की कोई key नहीं मिली (Render environment जाँचिए)"
            else:
                try:
                    resp = requests.get(urls[prov], headers={"Authorization": f"Bearer {lst[0].key}"}, timeout=15)
                    info["http"] = resp.status_code
                    if resp.status_code == 200:
                        ids = {str(m.get("id", "")).replace("models/", "") for m in resp.json().get("data", [])}
                        info["models_available"] = len(ids)
                        info["config_models"] = {m: (m in ids) for m in sorted(wanted.get(prov, []))}
                        if prov == "openrouter":
                            info["free_models"] = sorted(i for i in ids if i.endswith(":free"))[:60]
                        else:
                            info["sample"] = sorted(ids)[:40]
                    else:
                        info["problem"] = "key काम नहीं कर रही" if resp.status_code in (401, 403) else (resp.text or "")[:200].replace(lst[0].key, "***")
                except requests.RequestException as e:
                    info["problem"] = type(e).__name__
            out[prov] = info
        return jsonify(out)

    @app.post("/summarize")
    def summarize():
        """Phone sends raw episodes/summaries; relay returns a summary (+ facts, habits, notes). Nothing is stored here."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        kind = data.get("kind")
        prompt = memorykit.build_prompt(kind, data)
        if prompt is None:
            return jsonify(error="bad_request"), 400
        try:
            res = router.complete("fast", prompt, temperature=0.2, max_tokens=1200, priority=4)
        except AllBusy as e:
            return jsonify(degraded=True, retry_after_s=e.retry_after_s, errors=e.errors[-4:])
        out = memorykit.parse_summary(kind, res.text, set(personas))
        if out is None:
            log.warning("summarize bad json kind=%s", kind)
            return jsonify(degraded=True, retry_after_s=60, errors=["bad_json"])
        return jsonify(degraded=False, provider=res.provider, model=res.model, **out)

    @app.post("/agent/step")
    def agent_step():
        """Agent Engine: returns ONE next screen action as strict JSON. Permissions are checked here and on the phone."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        cid = data.get("character")
        persona = personas.get(cid)
        if persona is None or "screen_task" not in toolmod.allowed_for(cid, ["screen_task"]):
            return jsonify(error="not_allowed"), 403
        if not str(data.get("goal") or "").strip():
            return jsonify(error="bad_request"), 400
        screen_text = (data.get("screen") or {}).get("text") if isinstance(data.get("screen"), dict) else ""
        suspected = agentmod.detect_injection(screen_text)
        msgs = agentmod.build_messages(persona, {**data, "injection_suspected": suspected})
        try:
            res = router.complete(router.tier_for(cid), msgs, temperature=0.2, max_tokens=900, priority=2)
        except AllBusy as e:
            return jsonify(degraded=True, retry_after_s=e.retry_after_s, errors=e.errors[-4:])
        step = agentmod.parse_step(res.text)
        if step is None:
            log.warning("agent bad step char=%s", cid)
            return jsonify(degraded=True, retry_after_s=5, errors=["bad_step"])
        step["injection"] = bool(step["injection"] or suspected)
        return jsonify(degraded=False, step=step, provider=res.provider, model=res.model)

    @app.post("/agent/look")
    def agent_look():
        """Read one screenshot (vision tier). The image is processed and dropped; nothing is stored on the relay."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        cid = data.get("character")
        if personas.get(cid) is None or "screen_task" not in toolmod.allowed_for(cid, ["screen_task"]):
            return jsonify(error="not_allowed"), 403
        img = data.get("image_b64")
        if not isinstance(img, str) or not (200 <= len(img) <= 900_000):
            return jsonify(error="bad_image"), 400
        try:
            res = router.complete("vision", agentmod.build_look_messages(data), temperature=0.1, max_tokens=900, priority=2)
        except AllBusy as e:
            return jsonify(degraded=True, retry_after_s=e.retry_after_s, errors=e.errors[-4:])
        out = agentmod.parse_look(res.text)
        if out is None:
            return jsonify(degraded=True, retry_after_s=10, errors=["bad_look"])
        return jsonify(degraded=False, provider=res.provider, model=res.model, **out)

    @app.post("/rule_from_text")
    def rule_from_text():
        """Teach by talking: Hinglish -> one validated rule JSON. The phone shows it for approval before saving."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        if not str(data.get("text") or "").strip():
            return jsonify(error="bad_request"), 400
        ids = set(personas)
        try:
            res = router.complete("smart", rulesmod.build_messages(data, ids), temperature=0.1, max_tokens=900)
        except AllBusy as e:
            return jsonify(degraded=True, retry_after_s=e.retry_after_s, errors=e.errors[-4:])
        out = rulesmod.parse(res.text, ids)
        if out is None:
            return jsonify(degraded=True, retry_after_s=10, errors=["bad_json"])
        if "rule" in out:
            return jsonify(degraded=False, understood=True, rule=out["rule"], provider=res.provider, model=res.model)
        return jsonify(degraded=False, understood=False, question=out["question"])

    @app.post("/research")
    def research_ep():
        """Research Engine: privacy-scrubbed web search -> Hindi answer with sources, date, confidence. Stores nothing."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        cid = str(data.get("character") or "jarvis").lower()
        persona = personas.get(cid)
        if persona is None:
            return jsonify(error="unknown_character"), 404
        if not str(data.get("query") or "").strip():
            return jsonify(error="bad_request"), 400
        out = researchmod.run(router, persona, data["query"], data.get("redact") if isinstance(data.get("redact"), list) else [],
                              proactive=bool(data.get("proactive")))
        log.info("research char=%s degraded=%s conf=%s via=%s", cid, out.get("degraded"), out.get("confidence"), out.get("via"))
        return jsonify(**out)

    @app.get("/research/diag")
    def research_diag():
        """Run one tiny search and show exactly which path failed and why (status codes, no keys)."""
        if (r := guard()) is not None:
            return r
        out = researchmod.run(router, personas.get("jarvis") or next(iter(personas.values())), "capital of France")
        return jsonify(**out)

    @app.post("/family/ask")
    def family_ask():
        """Ask-the-family protocol: memory -> best peer -> research (max 2 hops, call and time budget). Returns every step."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        asker = str(data.get("asker") or "").lower()
        if asker not in personas:
            return jsonify(error="unknown_character", known=list(personas)), 404
        if not str(data.get("question") or "").strip():
            return jsonify(error="bad_request"), 400
        out = familymod.run(router, personas, {**data, "asker": asker})
        log.info("family ask asker=%s hops=%s calls=%s used=%s degraded=%s", asker, out["hops"], out["llm_calls"],
                 out["final"]["used"], out["degraded"])
        return jsonify(**out)

    @app.post("/proactive/topic")
    def proactive_topic():
        """Quiet helper: did the user just mention something worth preparing research for? Low priority; never raises."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        if not str(data.get("text") or "").strip():
            return jsonify(worth=False)
        out = researchmod.proactive_topic(router, data["text"], familymod.TALENT)
        return jsonify(**out)

    @app.get("/talent")
    def talent():
        if (r := guard()) is not None:
            return r
        return jsonify(familymod.TALENT["characters"])

    @app.get("/characters")
    def characters():
        if (r := guard()) is not None:
            return r
        return jsonify([{"id": p["id"], "name": p["name"], "role": p["role"]} for p in personas.values()])

    @app.get("/brain_status")
    def brain_status():
        if (r := guard()) is not None:
            return r
        return jsonify(router.status(list(personas)))

    @app.post("/chat")
    def chat():
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        cid = str(data.get("character") or "jarvis").lower()
        persona = personas.get(cid)
        if persona is None:
            return jsonify(error="unknown_character", known=list(personas)), 404
        message = str(data.get("message") or "").strip()[:4000]
        if not message:
            return jsonify(error="empty_message"), 400

        allowed = toolmod.allowed_for(cid, data.get("tools"))
        language = norm_lang(data.get("language"))
        messages = build_messages(persona, message, clean_history(data.get("history")),
                                  data.get("memory"), data.get("context"),
                                  tools=toolmod.describe(allowed) if allowed else None, language=language)
        tier = router.tier_for(cid)
        t0 = time.time()
        base = {"character": cid, "proposed_rule": None, "mood_delta": 0}
        try:
            res = router.complete(tier, messages)
        except AllBusy as e:
            log.warning("all busy char=%s errors=%s", cid, e.errors[-4:])
            return jsonify(speech=persona["busy_line"], degraded=True, retry_after_s=e.retry_after_s,
                           language_ok=True, provider=None, model=None,
                           errors=e.errors[-8:], tool_calls=[], **base)

        payload = finish_chat(res, allowed, messages, language, cid, tier, t0)
        return jsonify(**payload, **base)

    def finish_chat(res, allowed, messages, language, cid, tier, t0):
        """Shared by /chat and /chat/stream: parse the model output, language guard, validate tool calls."""
        if allowed:
            text, raw_calls = toolmod.parse_output(res.text)
        else:
            text, raw_calls = res.text, []
        lang_ok = language_matches(text, language) if text else True
        if not lang_ok:  # language guard: rewrite once on the stronger tier
            try:
                fix = router.complete("smart", messages + rewrite_request(text, language))
                fix_text = toolmod.parse_output(fix.text)[0] if allowed else fix.text
                if fix_text and language_matches(fix_text, language):
                    text, lang_ok, res = fix_text, True, fix
            except AllBusy:
                pass
        calls, notes = toolmod.validate_calls(raw_calls, allowed)
        if notes:
            log.warning("tool calls dropped char=%s notes=%s", cid, notes)
        text = strip_emoji(text) or text
        if not text and calls:
            text = "ठीक है।"
        log.info("chat char=%s tier=%s provider=%s model=%s attempts=%d lang_ok=%s tools=%s ms=%d",
                 cid, tier, res.provider, res.model, res.attempts, lang_ok, [c["name"] for c in calls], (time.time() - t0) * 1000)
        return dict(speech=text, degraded=False, retry_after_s=0, language_ok=lang_ok,
                    provider=res.provider, model=res.model, attempts=res.attempts,
                    latency_ms=int((time.time() - t0) * 1000), tool_calls=calls, tool_notes=notes)

    @app.post("/chat/stream")
    def chat_stream():
        """Same answer as /chat, but the words arrive as they are written (server-sent events):
        data: {"type":"delta","text":...} ... then data: {"type":"final", ...same JSON as /chat...}."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        cid = str(data.get("character") or "jarvis").lower()
        persona = personas.get(cid)
        if persona is None:
            return jsonify(error="unknown_character", known=list(personas)), 404
        message = str(data.get("message") or "").strip()[:4000]
        if not message:
            return jsonify(error="empty_message"), 400
        allowed = toolmod.allowed_for(cid, data.get("tools"))
        language = norm_lang(data.get("language"))
        messages = build_messages(persona, message, clean_history(data.get("history")), data.get("memory"), data.get("context"),
                                  tools=toolmod.describe(allowed) if allowed else None, language=language)
        tier = router.tier_for(cid)
        base = {"character": cid, "proposed_rule": None, "mood_delta": 0}

        def sse(obj):
            return "data: " + json.dumps(obj, ensure_ascii=False) + "\n\n"

        def gen():
            t0, ss, res = time.time(), SpeechStream(), None
            try:
                for kind, val in router.stream(tier, messages):
                    if kind == "delta":
                        d = ss.feed(val)
                        if d:
                            yield sse({"type": "delta", "text": d})
                    elif kind == "done":
                        res = val
                    else:  # the stream broke after words had started: say so, the phone keeps what it has
                        yield sse({"type": "error", "reason": val})
                        return
            except AllBusy as e:
                log.warning("all busy (stream) char=%s errors=%s", cid, e.errors[-4:])
                yield sse({"type": "final", "speech": persona["busy_line"], "degraded": True, "retry_after_s": e.retry_after_s,
                           "language_ok": True, "provider": None, "model": None, "errors": e.errors[-8:], "tool_calls": [], **base})
                return
            if res is None:
                return
            yield sse({"type": "final", **finish_chat(res, allowed, messages, language, cid, tier, t0), **base})

        return Response(stream_with_context(gen()), mimetype="text/event-stream",
                        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    @app.post("/embed")
    def embed():
        """Vectors for memory retrieval. Quiet failure: the phone falls back to keyword search."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        texts = data.get("texts")
        if not isinstance(texts, list) or not texts or len(texts) > 40 or not all(isinstance(t, str) and t.strip() for t in texts):
            return jsonify(error="bad_request"), 400
        try:
            vecs = router.embed([t.strip()[:1500] for t in texts], priority=int(data.get("priority") or 1))
        except AllBusy as e:
            return jsonify(degraded=True, retry_after_s=e.retry_after_s, errors=e.errors[-3:])
        return jsonify(degraded=False, vectors=vecs, dims=len(vecs[0]) if vecs else 0)

    @app.post("/vision/describe")
    def vision_describe():
        """Camera photo -> Hindi answer (read the text on it, identify what it is, or answer a question). Nothing is stored."""
        if (r := guard()) is not None:
            return r
        data = request.get_json(silent=True) or {}
        cid = data.get("character")
        if personas.get(cid) is None or "camera_look" not in toolmod.allowed_for(cid, ["camera_look"]):
            return jsonify(error="not_allowed"), 403
        img = data.get("image_b64")
        if not isinstance(img, str) or not (200 <= len(img) <= 1_800_000):
            return jsonify(error="bad_image"), 400
        try:
            res = router.complete("vision", agentmod.build_camera_messages(data, personas[cid]), temperature=0.1, max_tokens=900, priority=2)
        except AllBusy as e:
            return jsonify(degraded=True, retry_after_s=e.retry_after_s, errors=e.errors[-4:])
        out = agentmod.parse_camera(res.text)
        if out is None:
            return jsonify(degraded=True, retry_after_s=10, errors=["bad_vision_reply"])
        return jsonify(degraded=False, provider=res.provider, model=res.model, **out)

    app.config["ROUTER"] = router
    return app


def startup_report(app_obj):
    rt = app_obj.config.get("ROUTER")
    log.info("STARTUP APP_TOKEN set=%s", bool(os.environ.get("APP_TOKEN")))
    if rt:
        for prov, lst in rt.keys.items():
            log.info("STARTUP %s keys=%d%s", prov, len(lst), "" if lst else "  <-- NONE FOUND, check %s" % PROVIDERS[prov]["env"])


app = create_app()
startup_report(app)
