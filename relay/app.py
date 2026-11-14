"""Stark Family relay (Flask). Holds all API keys; the Android app never sees them.

Endpoints: GET /health, GET /test (open pages); GET /characters, /brain_status, /diag, POST /chat (auth: X-App-Token)
Run with ONE gunicorn worker so key cooldown state is shared (see render.yaml).
"""
import hmac
import logging
import os
import time

from flask import Flask, jsonify, request

from prompting import (build_messages, clean_history, language_ok, load_personas,
                       rewrite_request)
import requests

from router import PROVIDERS, AllBusy, Router, load_config
from test_page import PAGE

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("relay")

BASE = os.path.dirname(os.path.abspath(__file__))


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
        return jsonify(ok=True, time=int(time.time()), characters=list(personas))

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

        messages = build_messages(persona, message, clean_history(data.get("history")),
                                  data.get("memory"), data.get("context"))
        tier = router.tier_for(cid)
        t0 = time.time()
        base = {"character": cid, "tool_calls": [], "proposed_rule": None, "mood_delta": 0}
        try:
            res = router.complete(tier, messages)
        except AllBusy as e:
            log.warning("all busy char=%s errors=%s", cid, e.errors[-4:])
            return jsonify(speech=persona["busy_line"], degraded=True, retry_after_s=e.retry_after_s,
                           language_ok=True, provider=None, model=None,
                           errors=e.errors[-8:], **base)

        text, lang_ok = res.text, language_ok(res.text)
        if not lang_ok:  # language guard: rewrite once on the stronger tier
            try:
                fix = router.complete("smart", messages + rewrite_request(res.text))
                if language_ok(fix.text):
                    text, lang_ok, res = fix.text, True, fix
            except AllBusy:
                pass
        log.info("chat char=%s tier=%s provider=%s model=%s attempts=%d lang_ok=%s ms=%d",
                 cid, tier, res.provider, res.model, res.attempts, lang_ok, (time.time() - t0) * 1000)
        return jsonify(speech=text, degraded=False, retry_after_s=0, language_ok=lang_ok,
                       provider=res.provider, model=res.model, attempts=res.attempts,
                       latency_ms=int((time.time() - t0) * 1000), **base)

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
