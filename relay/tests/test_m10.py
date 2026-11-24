import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import family  # noqa: E402
import research  # noqa: E402
from app import create_app  # noqa: E402
from prompting import load_personas  # noqa: E402
from router import AllBusy, Result, Router  # noqa: E402

PERSONAS = load_personas()


class FakeRouter:
    """complete(): pops scripted replies (str or Exception). search_grounded(): scripted too."""

    def __init__(self, replies=(), search=None):
        self.replies, self.search, self.calls, self.search_calls = list(replies), search, [], 0
        self.cfg, self.keys = {"tiers": {}, "request": {}}, {}

    def now(self):
        return 1_800_000_000.0

    def tier_for(self, c):
        return "fast"

    def complete(self, tier, messages, temperature=None, max_tokens=None):
        self.calls.append(messages)
        if not self.replies:
            raise AllBusy(10, ["none_left"])
        r = self.replies.pop(0)
        if isinstance(r, Exception):
            raise r
        return Result(r, "fake", "m", "…", 1, tier)

    def search_grounded(self, prompt, max_tokens=1200):
        self.search_calls += 1
        if self.search is None or isinstance(self.search, Exception):
            raise self.search or AllBusy(10, ["gemini:x"])
        return self.search


def J(**kw):
    return json.dumps(kw, ensure_ascii=False)


GROUNDED = (J(answer="आज दिल्ली में तापमान 24 डिग्री है।", known="तापमान 24", guessed="", confidence=0.8, time_sensitive=True),
            [{"title": "IMD", "url": "https://mausam.imd.gov.in"}], "gemini", "gem")


class Scrub(unittest.TestCase):
    def test_removes_personal(self):
        q = research.scrub_query("Sunita 9876543210 a@b.com https://x.y/z news", ["Sunita"])
        self.assertEqual(q, "news")

    def test_sensitive_and_time(self):
        self.assertTrue(research.is_sensitive_topic("डिप्रेशन की दवा"))
        self.assertFalse(research.is_sensitive_topic("python list sort"))
        self.assertTrue(research.looks_time_sensitive("आज का मौसम"))
        self.assertFalse(research.looks_time_sensitive("पानी का रासायनिक सूत्र"))


class ResearchRun(unittest.TestCase):
    def test_grounded_ok(self):
        r = research.run(FakeRouter(search=GROUNDED), PERSONAS["friday"], "Delhi weather today")
        self.assertFalse(r["degraded"])
        self.assertEqual(r["sources"][0]["url"], "https://mausam.imd.gov.in")
        self.assertTrue(r["time_sensitive"])
        self.assertIn("retrieved_at", r)

    def test_proactive_sensitive_refused_without_search(self):
        fr = FakeRouter(search=GROUNDED)
        r = research.run(fr, PERSONAS["vision"], "diabetes symptoms", proactive=True)
        self.assertEqual(r["refused"], "sensitive")
        self.assertEqual(fr.search_calls, 0)

    def test_user_asked_sensitive_is_allowed(self):
        r = research.run(FakeRouter(search=GROUNDED), PERSONAS["vision"], "diabetes symptoms", proactive=False)
        self.assertNotIn("refused", r)

    def test_fallback_to_page_search(self):
        class Resp:
            status_code = 200
            text = ('<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">Title A</a>'
                    '<a class="result__snippet">snippet a</a>')

        fr = FakeRouter(replies=[J(answer="उदाहरण का जवाब हिंदी में यहाँ है।", known="x", guessed="", confidence=0.95, time_sensitive=False)])
        r = research.run(fr, PERSONAS["edith"], "some tech query", ddg=lambda *a, **k: Resp())
        self.assertFalse(r["degraded"])
        self.assertEqual(r["sources"][0]["url"], "https://example.com/a")
        self.assertLessEqual(r["confidence"], 0.6)  # snippets never earn high confidence

    def test_everything_down_degrades(self):
        class Resp:
            status_code = 500
            text = ""

        r = research.run(FakeRouter(), PERSONAS["edith"], "some tech query", ddg=lambda *a, **k: Resp())
        self.assertTrue(r["degraded"])


class Registry(unittest.TestCase):
    def test_best_peer_by_talent(self):
        self.assertEqual(family.rank_peers("phone ki battery jaldi khatam", "jarvis", personas=PERSONAS)[0][0], "edith")
        self.assertEqual(family.rank_peers("कल परीक्षा का syllabus", "friday", personas=PERSONAS)[0][0], "vision")

    def test_asker_excluded_and_adjustments(self):
        base = family.rank_peers("phone battery", "edith", personas=PERSONAS)
        self.assertNotIn("edith", [c for c, _, _ in base])
        adj = {"edith": {"devices": -0.4, "security": -0.4}}
        before = dict((c, sc) for c, sc, _ in family.rank_peers("phone battery", "jarvis", personas=PERSONAS))["edith"]
        after = dict((c, sc) for c, sc, _ in family.rank_peers("phone battery", "jarvis", adj, personas=PERSONAS))["edith"]
        self.assertLess(after, before)
        # adjustments are clamped, so a bad value from the phone cannot break ranking
        wild = dict((c, sc) for c, sc, _ in family.rank_peers("phone battery", "jarvis", {"edith": {"devices": -99}}, personas=PERSONAS))["edith"]
        self.assertGreaterEqual(wild, before - 0.4 - 1e-9)

    def test_word_boundary(self):
        self.assertNotIn("logistics", [d for d, _ in family.classify("syllabus")])


class AskFamily(unittest.TestCase):
    def run_it(self, fr, **extra):
        d = {"asker": "jarvis", "question": "मेरे फ़ोन की बैटरी जल्दी क्यों ख़त्म होती है?", "memory": []}
        d.update(extra)
        return family.run(fr, PERSONAS, d)

    def test_asker_knows(self):
        fr = FakeRouter([J(known=True, answer="स्क्रीन की चमक सबसे ज़्यादा बैटरी खाती है।", needs_fresh=False)])
        out = self.run_it(fr)
        self.assertEqual(out["hops"], 0)
        self.assertEqual(out["final"]["used"], ["self"])
        self.assertEqual(len(fr.calls), 1)

    def test_peer_answers_and_is_labelled(self):
        fr = FakeRouter([J(known=False, answer="", needs_fresh=False),
                         J(known=True, answer="बैकग्राउंड ऐप्स और ख़राब सिग्नल मुख्य वजह हैं।"),
                         "सर, एडिथ के मुताबिक़ बैकग्राउंड ऐप्स और ख़राब सिग्नल मुख्य वजह हैं।"])
        out = self.run_it(fr)
        kinds = [s["kind"] for s in out["steps"]]
        self.assertEqual(kinds, ["unsure", "ask_peer", "peer_answer", "final"])
        self.assertEqual(out["steps"][1]["to"], "edith")
        self.assertEqual(out["final"]["used"], ["peer:edith"])
        self.assertEqual(out["talent_updates"][0]["delta"], 0.03)
        self.assertEqual(out["hops"], 1)

    def test_peer_unknown_then_research_max_two_hops(self):
        fr = FakeRouter([J(known=False, answer="", needs_fresh=False), J(known=False, answer=""),
                         "सर, खोज के मुताबिक़ 24 डिग्री है।"], search=GROUNDED)
        out = self.run_it(fr)
        self.assertEqual(out["hops"], 2)
        self.assertEqual(out["final"]["used"], ["research"])
        self.assertEqual(out["research_note"]["confidence"], 0.8)
        self.assertEqual(out["talent_updates"][0]["delta"], -0.05)
        self.assertEqual(fr.search_calls, 1)

    def test_fresh_question_skips_peers(self):
        fr = FakeRouter([J(known=False, answer="", needs_fresh=True), "सर, आज 24 डिग्री है।"], search=GROUNDED)
        out = self.run_it(fr, question="आज दिल्ली का मौसम कैसा है?")
        self.assertNotIn("ask_peer", [s["kind"] for s in out["steps"]])
        self.assertEqual(out["hops"], 1)

    def test_nothing_found_is_honest(self):
        fr = FakeRouter([J(known=False, answer="", needs_fresh=False), J(known=False, answer=""),
                         "सर, मैंने एडिथ से पूछा और इंटरनेट पर खोजा, पर पक्का जवाब नहीं मिला।"], search=AllBusy(10, ["x"]))
        out = self.run_it(fr)
        self.assertTrue(out["final"]["honest_gap"])
        self.assertFalse(out["final"]["used"])

    def test_research_off_asks_second_peer(self):
        fr = FakeRouter([J(known=False, answer="", needs_fresh=False), J(known=False, answer=""),
                         J(known=True, answer="दूसरे साथी का जवाब हिंदी में।"), "सर, यह जवाब मिला।"])
        out = self.run_it(fr, allow_research=False, question="मेरे फ़ोन का app crash और error क्यों आता है?")
        self.assertEqual(out["hops"], 2)
        self.assertEqual(fr.search_calls, 0)
        self.assertEqual(len([s for s in out["steps"] if s["kind"] == "ask_peer"]), 2)

    def test_budget_guard_never_loops(self):
        fr = FakeRouter([AllBusy(5, ["x"])] * 10)
        out = self.run_it(fr)
        self.assertTrue(out["degraded"])
        self.assertLessEqual(len(fr.calls), family.TALENT["limits"]["max_llm_calls"])
        self.assertIn("speech", out["final"])

    def test_peer_never_exceeds_limits(self):
        fr = FakeRouter([J(known=False, answer="", needs_fresh=False)] + [J(known=False, answer="")] * 8, search=AllBusy(5, ["x"]))
        out = self.run_it(fr)
        self.assertLessEqual(out["hops"], family.TALENT["limits"]["max_hops"])
        self.assertLessEqual(out["llm_calls"], family.TALENT["limits"]["max_llm_calls"])


class Endpoints(unittest.TestCase):
    def client(self, fr):
        app = create_app(router=fr, personas=PERSONAS, token="tok")
        return app.test_client()

    H = {"X-App-Token": "tok"}

    def test_auth_required(self):
        c = self.client(FakeRouter())
        self.assertEqual(c.post("/research", json={"query": "x"}).status_code, 401)
        self.assertEqual(c.post("/family/ask", json={"asker": "jarvis", "question": "x"}).status_code, 401)

    def test_research_endpoint(self):
        c = self.client(FakeRouter(search=GROUNDED))
        r = c.post("/research", json={"character": "friday", "query": "Delhi weather today"}, headers=self.H)
        self.assertEqual(r.status_code, 200)
        self.assertFalse(r.get_json()["degraded"])

    def test_family_endpoint_validates(self):
        c = self.client(FakeRouter())
        self.assertEqual(c.post("/family/ask", json={"asker": "nobody", "question": "x"}, headers=self.H).status_code, 404)
        self.assertEqual(c.post("/family/ask", json={"asker": "jarvis", "question": " "}, headers=self.H).status_code, 400)

    def test_new_tools_are_validated_in_chat(self):
        import tools as T
        calls, notes = T.validate_calls([{"name": "research", "args": {"query": "latest news"}},
                                         {"name": "ask_family", "args": {}}], ["research", "ask_family"])
        self.assertEqual([c["name"] for c in calls], ["research"])
        self.assertTrue(notes)
        for cid in PERSONAS:
            self.assertIn("research", T.allowed_for(cid, ["research"]))


class GroundedSearchInRouter(unittest.TestCase):
    CFG = {"request": {"timeout_s": 5, "max_attempts_per_provider": 3, "max_attempts_total": 8, "temperature": 0.7, "max_tokens": 100},
           "breaker": {"threshold": 6, "open_s": 60}, "tiers": {"fast": [{"provider": "gemini", "model": "g"}]},
           "research": {"model": "gem-s", "timeout_s": 5}, "characters": {}}

    class R:
        def __init__(self, status=200, body=None, headers=None):
            self.status_code, self._b, self.headers, self.text = status, body, headers or {}, "x"

        def json(self):
            return self._b

    def make(self, script):
        calls = []

        def tr(url, headers=None, json=None, timeout=None):
            calls.append((url, headers["x-goog-api-key"], json))
            return script(headers["x-goog-api-key"])

        return Router(self.CFG, env={"GEMINI_KEYS": '["k1111","k2222"]'}, transport=tr), calls

    def test_ok_with_sources(self):
        body = {"candidates": [{"content": {"parts": [{"text": "जवाब"}]},
                                "groundingMetadata": {"groundingChunks": [{"web": {"uri": "https://a.com", "title": "A"}},
                                                                          {"web": {"uri": "https://a.com", "title": "A"}}]}}]}
        r, calls = self.make(lambda k: self.R(200, body))
        text, src, prov, model = r.search_grounded("q")
        self.assertEqual((text, prov, model), ("जवाब", "gemini", "gem-s"))
        self.assertEqual(src, [{"title": "A", "url": "https://a.com"}])  # de-duplicated
        self.assertIn({"google_search": {}}, calls[0][2]["tools"])
        self.assertIn("gem-s:generateContent", calls[0][0])

    def test_429_rotates_key(self):
        body = {"candidates": [{"content": {"parts": [{"text": "ठीक"}]}}]}
        r, calls = self.make(lambda k: self.R(429, None, {"retry-after": "30"}) if k == "k1111" else self.R(200, body))
        text, *_ = r.search_grounded("q")
        self.assertEqual(text, "ठीक")
        self.assertEqual([c[1] for c in calls], ["k1111", "k2222"])

    def test_all_fail_raises_busy(self):
        r, _ = self.make(lambda k: self.R(500))
        with self.assertRaises(AllBusy):
            r.search_grounded("q")


if __name__ == "__main__":
    unittest.main()
