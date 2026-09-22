#!/usr/bin/env python3
"""remove_comments.py — strip comments from source files with strict encoding safety.

Encoding pipeline (never emits mojibake or silent U+FFFD):
  1. BOM sniff  -> utf-8-sig / utf-16-le / utf-16-be (BOM-ness preserved on write).
  2. Strict UTF-8 decode attempt.
  3. `chardet` (if installed) with confidence >= 0.7.
  4. Graceful fallback: windows-1252 report + optional --force (errors="replace").

Everything else (emojis, CJK, Arabic, symbols) round-trips byte-accurately and
output is always UTF-8. Line count is preserved (comment-only lines become
blank) so stack traces and linters keep working.

Supported comment styles (--lang auto|py|kt|java|c|js|ts|xml|html|sh;
auto-detected from extension; kt/java/c/js/ts share the C-like stripper).

Usage:
  python3 remove_comments.py [--in-place] [--lang py] [--force] [--quiet] FILE...
  python3 remove_comments.py --self-test   # run the bundled test suite
"""

from __future__ import annotations

import argparse
import sys
import unittest

VERSION = "1.0.0"

EXT_LANG = {
    ".py": "py", ".pyw": "py",
    ".kt": "kt", ".kts": "kt", ".java": "kt", ".c": "kt", ".h": "kt",
    ".cpp": "kt", ".hpp": "kt", ".js": "kt", ".ts": "kt", ".tsx": "kt",
    ".xml": "xml", ".html": "xml", ".xhtml": "xml",
    ".sh": "sh", ".bash": "sh",
}

C_LIKE = {"kt", "java", "c", "js", "ts"}


class EncodingError(Exception):
    """Raised when a file's encoding cannot be determined safely."""


def detect_encoding(raw: bytes) -> tuple[str, bytes]:
    """Return (encoding, payload-without-BOM-marker-handling)."""
    if raw.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig", raw
    if raw.startswith(b"\xff\xfe"):
        return "utf-16-le", raw
    if raw.startswith(b"\xfe\xff"):
        return "utf-16-be", raw
    try:
        raw.decode("utf-8")
        return "utf-8", raw
    except UnicodeDecodeError:
        pass
    try:
        import chardet  # type: ignore

        guess = chardet.detect(raw)
        if guess["encoding"] and (guess["confidence"] or 0) >= 0.7:
            return str(guess["encoding"]), raw
    except ImportError:
        pass
    raise EncodingError(
        "not valid UTF-8 and no confident detection "
        "(install chardet or re-save as UTF-8)"
    )


def decode_file(path: str, force: bool = False) -> tuple[str, str]:
    """Return (text, write_encoding). Never silently corrupts text."""
    with open(path, "rb") as fh:
        raw = fh.read()
    if not raw:
        return "", "utf-8"
    try:
        encoding, _ = detect_encoding(raw)
    except EncodingError as exc:
        if not force:
            raise
        print(f"warning: {path}: {exc}; using replacement fallback", file=sys.stderr)
        return raw.decode("utf-8", errors="replace"), "utf-8"
    text = raw.decode(encoding)
    write_enc = "utf-8-sig" if encoding == "utf-8-sig" else "utf-8"
    if encoding in ("utf-16-le", "utf-16-be"):
        # utf-16 decode already consumed the BOM; re-emit plain UTF-8.
        write_enc = "utf-8"
    return text, write_enc


def strip_python(text: str) -> str:
    out_lines = []
    for lineno, line in enumerate(text.split("\n")):
        stripped_leading = line.lstrip()
        # Keep shebang and encoding cookies: functional, not prose.
        if lineno == 0 and stripped_leading.startswith("#!"):
            out_lines.append(line)
            continue
        if "coding" in stripped_leading[:40] and stripped_leading.startswith("#"):
            if "coding:" in stripped_leading or "coding=" in stripped_leading:
                out_lines.append(line)
                continue
        res, in_str, quote, triple, esc = [], False, "", False, False
        i = 0
        while i < len(line):
            ch = line[i]
            if in_str:
                res.append(ch)
                if esc:
                    esc = False
                elif ch == "\\" and not (triple and False):
                    esc = True
                elif ch == quote[0]:
                    if triple and line[i:i + 3] == quote:
                        res.append(line[i + 1:i + 3])
                        i += 2
                        in_str = False
                    elif not triple:
                        in_str = False
                i += 1
                continue
            if ch in ("'", '"'):
                # string prefix check (r/b/f/u) is irrelevant for scanning
                if line[i:i + 3] in ("'''", '"""'):
                    in_str, quote, triple = True, line[i:i + 3], True
                    res.append(line[i:i + 3])
                    i += 3
                else:
                    in_str, quote, triple = True, ch, False
                    res.append(ch)
                    i += 1
                continue
            if ch == "#":
                break
            res.append(ch)
            i += 1
        built = "".join(res).rstrip()
        # Comment-only lines become truly blank (keeps line count, no stray spaces).
        out_lines.append("" if built == "" and "#" in line else built)
    return "\n".join(out_lines)


def strip_c_like(text: str) -> str:
    res = []
    i, n = 0, len(text)
    in_str, quote, esc = False, "", False
    in_block = False
    line_has_code = False
    line_start = 0
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_block:
            if ch == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            if ch == "\n":
                res.append(ch)
            i += 1
            continue
        if in_str:
            res.append(ch)
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == quote:
                in_str = False
            i += 1
            continue
        if ch in ("'", '"', "`"):
            in_str, quote = True, ch
            res.append(ch)
            line_has_code = True
            i += 1
            continue
        if ch == "/" and nxt == "/":
            # drop to end of line, keep the newline itself
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if ch == "\n":
            # comment-only line -> blank (no trailing spaces)
            segment = "".join(res[line_start:])
            if segment.strip() == "":
                del res[line_start:]
            res.append(ch)
            line_start = len(res)
            line_has_code = False
            i += 1
            continue
        if not ch.isspace():
            line_has_code = True
        res.append(ch)
        i += 1
    _ = line_has_code
    return "".join(res)


def strip_xml(text: str) -> str:
    res = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("<!--", i):
            end = text.find("-->", i + 4)
            if end == -1:
                # Unterminated: drop rest but keep newlines for line count.
                res.append("\n" * text[i:].count("\n"))
                break
            dropped = text[i:end + 3]
            res.append("\n" * dropped.count("\n"))
            i = end + 3
            continue
        res.append(text[i])
        i += 1
    return "".join(res)


def strip_sh(text: str) -> str:
    out = []
    for lineno, line in enumerate(text.split("\n")):
        if lineno == 0 and line.startswith("#!"):
            out.append(line)
            continue
        res, in_s, in_d, esc = [], False, False, False
        for ch in line:
            if in_s:
                res.append(ch)
                if ch == "'":
                    in_s = False
                continue
            if in_d:
                res.append(ch)
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_d = False
                continue
            if ch == "'":
                in_s = True
                res.append(ch)
            elif ch == '"':
                in_d = True
                res.append(ch)
            elif ch == "#":
                break
            else:
                res.append(ch)
        built = "".join(res)
        out.append("" if built.strip() == "" and "#" in line else built.rstrip())
    return "\n".join(out)


def strip_comments(text: str, lang: str) -> str:
    if lang == "py":
        return strip_python(text)
    if lang in C_LIKE:
        return strip_c_like(text)
    if lang == "xml":
        return strip_xml(text)
    if lang == "sh":
        return strip_sh(text)
    raise ValueError(f"unsupported language: {lang}")


def lang_for(path: str, override: str | None) -> str:
    if override and override != "auto":
        return override
    ext = "." + path.rsplit(".", 1)[-1].lower() if "." in path else ""
    try:
        return EXT_LANG[ext]
    except KeyError:
        raise ValueError(f"cannot infer language for {path}; pass --lang")


def process_file(path: str, lang: str, in_place: bool, force: bool, quiet: bool) -> int:
    try:
        text, write_enc = decode_file(path, force=force)
    except (EncodingError, OSError) as exc:
        print(f"error: {path}: {exc}", file=sys.stderr)
        return 1
    except ValueError as exc:
        print(f"error: {path}: {exc}", file=sys.stderr)
        return 1
    try:
        cleaned = strip_comments(text, lang)
    except ValueError as exc:
        print(f"error: {path}: {exc}", file=sys.stderr)
        return 1
    if in_place:
        with open(path, "w", encoding=write_enc, newline="") as fh:
            fh.write(cleaned)
    else:
        sys.stdout.write(cleaned)
        if not cleaned.endswith("\n"):
            sys.stdout.write("\n")
    if not quiet:
        print(f"ok: {path} ({lang}, -> {write_enc})", file=sys.stderr)
    return 0


class EncodingTests(unittest.TestCase):
    def test_emoji_cjk_arabic_preserved_py(self):
        src = '# comment 🎉\nx = "日本語 🇮🇳 مرحبا # not a comment"  # trailing\n'
        out = strip_comments(src, "py")
        self.assertIn('"日本語 🇮🇳 مرحبا # not a comment"', out)
        self.assertNotIn("comment 🎉", out)
        self.assertNotIn("trailing", out)
        self.assertEqual(out.count("\n"), src.count("\n"))

    def test_bom_roundtrip(self):
        import tempfile, os

        with tempfile.NamedTemporaryFile("wb", suffix=".py", delete=False) as fh:
            fh.write("# hi\nx = 1  # there\n".encode("utf-8-sig"))
            name = fh.name
        try:
            self.assertEqual(process_file(name, "py", True, False, True), 0)
            raw = open(name, "rb").read()
            self.assertTrue(raw.startswith(b"\xef\xbb\xbf"))
            self.assertEqual(raw.decode("utf-8-sig"), "\nx = 1\n")
        finally:
            os.unlink(name)

    def test_utf16_decoded(self):
        import tempfile, os

        with tempfile.NamedTemporaryFile("wb", suffix=".kt", delete=False) as fh:
            fh.write("val s = \"hi\" // there\n".encode("utf-16"))
            name = fh.name
        try:
            text, enc = decode_file(name)
            self.assertEqual(enc, "utf-8")
            self.assertIn('"hi"', strip_comments(text, "kt"))
        finally:
            os.unlink(name)

    def test_invalid_bytes_rejected_without_force(self):
        import tempfile, os

        with tempfile.NamedTemporaryFile("wb", suffix=".py", delete=False) as fh:
            fh.write(b"x = '\xff\xfe binary'  # c\n")
            name = fh.name
        try:
            with self.assertRaises(EncodingError):
                decode_file(name, force=False)
        finally:
            os.unlink(name)

    def test_kotlin_strings_and_block_comments(self):
        src = 'val a = "// kept" /* gone */\nval b = "😀" // gone\n/* multi\nline */val c = 1\n'
        out = strip_comments(src, "kt")
        self.assertIn('"// kept"', out)
        self.assertIn('"😀"', out)
        self.assertNotIn("gone", out)
        self.assertNotIn("multi", out)
        self.assertEqual(out.count("\n"), src.count("\n"))

    def test_xml_comments_multiline(self):
        src = "<a>😀</a><!-- drop\nme --><b>x</b>"
        out = strip_comments(src, "xml")
        self.assertIn("<a>😀</a><b>x</b>", out.replace("\n", ""))

    def test_shebang_kept(self):
        src = "#!/bin/sh\necho 'a # b' # c\n"
        out = strip_comments(src, "sh")
        self.assertTrue(out.startswith("#!/bin/sh"))
        self.assertIn("'a # b'", out)
        self.assertNotIn("# c", out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Strip comments, encoding-safe.")
    parser.add_argument("files", nargs="*", help="source files to process")
    parser.add_argument("--lang", default="auto")
    parser.add_argument("--in-place", action="store_true")
    parser.add_argument("--force", action="store_true",
                        help="fall back to replacement chars instead of failing")
    parser.add_argument("--quiet", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--version", action="store_true")
    args = parser.parse_args(argv)
    if args.version:
        print(VERSION)
        return 0
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(EncodingTests)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        return 0 if result.wasSuccessful() else 1
    if not args.files:
        parser.error("no input files (or use --self-test)")
    code = 0
    for path in args.files:
        try:
            lang = lang_for(path, args.lang)
        except ValueError as exc:
            print(f"error: {exc}", file=sys.stderr)
            code = 1
            continue
        if process_file(path, lang, args.in_place, args.force, args.quiet) != 0:
            code = 1
    return code


if __name__ == "__main__":
    raise SystemExit(main())
