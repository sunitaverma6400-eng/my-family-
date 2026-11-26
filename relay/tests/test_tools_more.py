import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import rules as R  # noqa: E402
import tools as T  # noqa: E402

NEW = ["add_calendar_event", "set_brightness", "bluetooth_panel", "device_info", "copy_text", "media_control", "recall",
       "usage_stats", "read_notifications", "dismiss_notifications", "send_sms", "send_whatsapp", "share_text",
       "clear_cache", "edit_rule", "delegate"]
RISKY = {"read_notifications", "dismiss_notifications", "send_sms", "send_whatsapp", "share_text", "clear_cache", "edit_rule"}


def one(name, args, who="jarvis"):
    calls, notes = T.validate_calls([{"name": name, "args": args}], T.allowed_for(who, NEW + list(T.REG["tools"])))
    return calls, notes


class NewTools(unittest.TestCase):
    def test_all_registered_and_validated(self):
        for n in NEW:
            self.assertIn(n, T.REG["tools"])
            self.assertIn(n, T.VALIDATORS)
            self.assertIn(n, T.REG["permissions"]["jarvis"])

    def test_risky_ones_always_confirm(self):
        for n in RISKY:
            self.assertEqual(T.REG["tools"][n]["risk"], "high", n)
        calls, _ = one("send_sms", {"contact": "Mummy", "text": "देर से आऊँगा"})
        self.assertTrue(calls[0]["confirm"])
        calls, _ = one("media_control", {"action": "next"})
        self.assertFalse(calls[0]["confirm"])

    def test_argument_validation(self):
        self.assertTrue(one("add_calendar_event", {"title": "Exam", "date": "2026-12-01", "hour": 9})[0])
        self.assertFalse(one("add_calendar_event", {"title": "Exam", "date": "2026-13-45", "hour": 9})[0])
        self.assertFalse(one("add_calendar_event", {"title": "Exam", "date": "kal", "hour": 9})[0])
        self.assertFalse(one("set_brightness", {"percent": 150})[0])
        self.assertFalse(one("media_control", {"action": "explode"})[0])
        self.assertFalse(one("send_sms", {"contact": "Mummy"})[0])
        self.assertFalse(one("delegate", {"to": "hacker", "task": "x"})[0])
        self.assertEqual(one("delegate", {"to": "edith", "task": "battery जाँचो"})[0][0]["args"]["to"], "edith")
        self.assertEqual(one("device_info", {})[0][0]["args"]["what"], "all")
        self.assertEqual(one("usage_stats", {})[0][0]["args"]["days"], 1)

    def test_permissions_follow_the_pdf_table(self):
        P = T.REG["permissions"]
        # Vision: no device-control tools at all
        for n in ("send_sms", "send_whatsapp", "media_control", "torch", "clear_cache", "read_notifications", "set_brightness"):
            self.assertNotIn(n, P["vision"])
        # Karen: tips only
        self.assertNotIn("send_sms", P["karen"])
        # Friday: music, notifications, messages
        for n in ("media_control", "read_notifications", "send_sms", "send_whatsapp", "add_calendar_event"):
            self.assertIn(n, P["friday"])
        # Edith: device / usage / security audit; cannot send messages
        for n in ("device_info", "usage_stats"):
            self.assertIn(n, P["edith"])
        self.assertNotIn("send_sms", P["edith"])
        # Dum-E and U: chores
        for c in ("dume", "u"):
            self.assertIn("clear_cache", P[c])
            self.assertNotIn("send_sms", P[c])
        # Ultron: wide read access, never sends or deletes on his own
        for n in ("usage_stats", "read_notifications", "recall", "device_info"):
            self.assertIn(n, P["ultron"])
        for n in ("send_sms", "send_whatsapp", "clear_cache", "dismiss_notifications", "edit_rule", "call_contact"):
            self.assertNotIn(n, P["ultron"])
        # Jarvis: everything
        self.assertEqual(set(P["jarvis"]), set(T.REG["tools"]))

    def test_rules_can_use_messages_only_at_high_level(self):
        for n in ("send_sms", "send_whatsapp", "call_contact"):
            self.assertIn(n, R.RULE_TOOLS)
            self.assertIn(n, R.HIGH)
        self.assertNotIn("read_notifications", R.RULE_TOOLS)
        self.assertNotIn("clear_cache", R.RULE_TOOLS)


if __name__ == "__main__":
    unittest.main()
