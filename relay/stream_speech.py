"""Turns a stream of raw model text into a stream of the *speech* words only.

The model answers either with plain text or with our JSON shape {"speech": "...", "tool_calls": [...]}. While it is still
writing, we pull out the characters of the "speech" value as they arrive (decoding JSON escapes), so the app can show words
immediately. The authoritative answer is still parsed from the complete text at the end."""
import re

from prompting import _EMOJI

_THINK_CLOSED = re.compile(r"<think>.*?</think>", re.S)
_ESC = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", '"': '"', "\\": "\\", "/": "/"}


def decode_partial(body):
    """Decode a JSON string body up to its closing quote (or up to the end if it is still open). Never splits an escape."""
    out, i, n = [], 0, len(body)
    while i < n:
        c = body[i]
        if c == '"':
            break
        if c != "\\":
            out.append(c)
            i += 1
            continue
        if i + 1 >= n:
            break  # lone backslash at the end: wait for the next chunk
        e = body[i + 1]
        if e == "u":
            if i + 6 > n:
                break
            try:
                out.append(chr(int(body[i + 2:i + 6], 16)))
            except ValueError:
                pass
            i += 6
            continue
        out.append(_ESC.get(e, e))
        i += 2
    return "".join(out)


class SpeechStream:
    def __init__(self):
        self.buf = ""
        self.emitted = 0

    def _visible(self):
        v = _THINK_CLOSED.sub("", self.buf)
        k = v.find("<think>")
        if k != -1:  # a thinking block that is still open: hide it
            v = v[:k]
        return v.lstrip()

    def feed(self, chunk):
        """Returns the newly available speech text (possibly empty)."""
        self.buf += chunk
        vis = self._visible()
        if not vis:
            return ""
        if "```json".startswith(vis.lower()) or vis in ("`", "``"):
            return ""
        vis = re.sub(r"^```(?:json)?\s*", "", vis, flags=re.I)
        if not vis:
            return ""
        if vis[0] == "{":
            m = re.search(r'"speech"\s*:\s*"', vis)
            if not m:
                return ""
            text = decode_partial(vis[m.end():])
        else:
            text = vis
        new = text[self.emitted:]
        self.emitted = len(text)
        return _EMOJI.sub("", new)
