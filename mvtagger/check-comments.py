#!/usr/bin/env python3
"""
Catches a Kotlin comment that swallows the rest of its file.

Kotlin block comments nest. So a doc comment that mentions a MIME type --

    /** Falls back to a bare video/* when nothing else matches. */

-- does not end where it looks like it ends: the `/*` inside it opens a second
comment, the `*/` closes only that one, and everything after it, to the end of
the file, is comment. The compiler says nothing until it reaches the last line
and reports "Unclosed comment" there, hundreds of lines from the cause, while
every declaration in the file is reported as unresolved from every other file
that uses it. It cost a CI round trip to read that backwards.

Two things are checked, both of which are always mistakes rather than style:

  a block comment still open at the end of the file;
  a `/*` inside a doc comment, which is the accident above -- deliberately
  nesting a comment inside documentation is not a thing anyone means to do.

Deliberate nesting in a plain `/* */` comment, which is how you comment out a
block of code that already has comments in it, is left alone.

Give it directories to check, or none to walk everything beside this script.
"""

import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent


def scan(text):
    """Yields (line, message) for each problem, walking the file as Kotlin lexes it."""
    problems = []
    i = 0
    n = len(text)
    line = 1
    depth = 0
    # Where the outermost open comment began, and whether it is documentation.
    opened_at = 0
    is_doc = False
    reported_nesting = False

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if ch == "\n":
            line += 1
            i += 1
            continue

        if depth > 0:
            if ch == "/" and nxt == "*":
                # Only the first one is worth reporting: once a doc comment has
                # swallowed the file, every later `/**` in it looks nested too,
                # and a screenful of copies of the same message helps nobody.
                if is_doc and not reported_nesting:
                    reported_nesting = True
                    problems.append((
                        line,
                        "'/*' inside the doc comment opened on line %d. Kotlin block "
                        "comments nest, so this one does not end where it looks like "
                        "it does and the rest of the file is swallowed. Reword it -- "
                        "a wildcard MIME type is the usual culprit." % opened_at,
                    ))
                depth += 1
                i += 2
                continue
            if ch == "*" and nxt == "/":
                depth -= 1
                i += 2
                continue
            i += 1
            continue

        # Outside any comment: skip over the things that can contain a slash-star
        # without meaning one.
        if ch == '"' and text[i:i + 3] == '"""':
            end = text.find('"""', i + 3)
            if end < 0:
                return problems + [(line, "unterminated raw string")]
            line += text.count("\n", i, end)
            i = end + 3
            continue

        if ch == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                elif text[i] == "\n":
                    break  # A broken literal; the compiler will say so.
                i += 1
            i += 1
            continue

        if ch == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue

        if ch == "/" and nxt == "/":
            end = text.find("\n", i)
            i = n if end < 0 else end
            continue

        if ch == "/" and nxt == "*":
            depth = 1
            opened_at = line
            is_doc = text[i:i + 3] == "/**"
            i += 2
            continue

        i += 1

    if depth > 0:
        problems.append((
            opened_at,
            "this block comment is never closed -- the rest of the file is inside it",
        ))
    return problems


def main(argv):
    roots = [pathlib.Path(a) for a in argv] or [HERE]
    files = sorted(f for root in roots for f in root.rglob("*.kt"))
    files = [f for f in files if "/build/" not in str(f)]
    if not files:
        print("check-comments: no Kotlin sources found", file=sys.stderr)
        return 1

    bad = 0
    for path in files:
        for line, message in scan(path.read_text(encoding="utf-8")):
            bad += 1
            print("%s:%d: %s" % (path, line, message), file=sys.stderr)

    if bad:
        print("check-comments: %d problem(s)" % bad, file=sys.stderr)
        return 1
    print("check-comments: %d Kotlin files, every comment closes where it looks like it does." % len(files))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
