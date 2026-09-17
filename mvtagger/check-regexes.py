#!/usr/bin/env python3
"""Fail the build if a regex literal is one Android would refuse.

The tests for :core run on a JVM, because that is what makes them fast enough
to run on every push without an emulator. The cost of that is this: the JVM and
Android do not use the same regex engine. The JVM has its own; Android's is
ICU's, and ICU is stricter.

A pattern the JVM accepts and ICU rejects therefore passes every test and then
kills the app on the phone -- which is exactly what happened:

    Regex(\"\"\"\\{(\\w+)}\"\"\")     # fine on the JVM, U_REGEX_RULE_SYNTAX on ICU

That one sat in a static initialiser that runs during the first launch, so the
app died before drawing anything, with Android reporting only "this app has a
bug". Nothing in 59 passing tests said a word about it.

So every regex literal in the Kotlin sources is compiled against ICU here,
which is the same engine the phone will use.

    python3 check-regexes.py <source dir> [<source dir> ...]
"""
import ctypes
import glob
import re
import sys


def load_icu():
    """The ICU regex entry points, whatever version is installed."""
    i18n_paths = sorted(glob.glob('/usr/lib/*/libicui18n.so.*')) or \
        sorted(glob.glob('/usr/lib/libicui18n.so.*'))
    uc_paths = sorted(glob.glob('/usr/lib/*/libicuuc.so.*')) or \
        sorted(glob.glob('/usr/lib/libicuuc.so.*'))
    if not i18n_paths or not uc_paths:
        return None

    i18n = ctypes.CDLL(i18n_paths[0])
    uc = ctypes.CDLL(uc_paths[0])

    # ICU exports versioned symbols, e.g. uregex_open_74.
    version = i18n_paths[0].rsplit('.so.', 1)[1].split('.')[0]
    try:
        opener = getattr(i18n, 'uregex_open_' + version)
        closer = getattr(i18n, 'uregex_close_' + version)
        namer = getattr(uc, 'u_errorName_' + version)
    except AttributeError:
        return None

    opener.restype = ctypes.c_void_p
    opener.argtypes = [ctypes.c_void_p, ctypes.c_int32, ctypes.c_uint32,
                       ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32)]
    closer.argtypes = [ctypes.c_void_p]
    namer.restype = ctypes.c_char_p
    namer.argtypes = [ctypes.c_int32]
    return opener, closer, namer


def compiles(icu, pattern):
    """(ok, message) for compiling `pattern` with ICU."""
    opener, closer, namer = icu
    buf = ctypes.create_string_buffer(pattern.encode('utf-16-le'))
    status = ctypes.c_int32(0)
    parse_error = ctypes.create_string_buffer(256)
    handle = opener(buf, len(pattern), 0, parse_error, ctypes.byref(status))
    if handle:
        closer(ctypes.c_void_p(handle))
    code = status.value
    return code <= 0, namer(code).decode()


# Regex("""...""") and Regex("..."), the two ways they are written here.
RAW = re.compile(r'Regex\(\s*"""(.*?)"""', re.DOTALL)
# The (?!"") keeps this from also matching the opening of a """ literal, which
# would otherwise be read as an empty pattern and reported as broken.
QUOTED = re.compile(r'Regex\(\s*"(?!"")((?:[^"\\\n]|\\.)*)"')


def unescape_kotlin(text):
    """A Kotlin escaped string literal as the characters it denotes."""
    out, i = [], 0
    while i < len(text):
        if text[i] == '\\' and i + 1 < len(text):
            nxt = text[i + 1]
            mapping = {'n': '\n', 'r': '\r', 't': '\t', '\\': '\\',
                       '"': '"', "'": "'", '$': '$', 'b': '\b'}
            if nxt == 'u' and i + 5 < len(text):
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(mapping.get(nxt, nxt))
            i += 2
            continue
        out.append(text[i])
        i += 1
    return ''.join(out)


# A ${...} template is filled in at runtime; substitute something harmless so
# the rest of the pattern is still checked.
TEMPLATE = re.compile(r'\$\{[^}]*\}|\$\w+')


def patterns_in(path):
    text = open(path, encoding='utf-8').read()
    for match in RAW.finditer(text):
        yield match.group(1), text[:match.start()].count('\n') + 1
    for match in QUOTED.finditer(text):
        yield unescape_kotlin(match.group(1)), text[:match.start()].count('\n') + 1


def main(argv):
    icu = load_icu()
    if icu is None:
        print('check-regexes: no ICU library found, skipping', file=sys.stderr)
        return 0

    failures = []
    checked = 0
    for root in argv:
        for path in sorted(glob.glob(root + '/**/*.kt', recursive=True)):
            for pattern, line in patterns_in(path):
                probe = TEMPLATE.sub('X', pattern)
                ok, message = compiles(icu, probe)
                checked += 1
                if not ok:
                    failures.append((path, line, pattern, message))

    for path, line, pattern, message in failures:
        print('check-regexes: %s:%d\n    %r\n    Android (ICU) rejects this: %s'
              % (path, line, pattern, message), file=sys.stderr)

    if failures:
        print('\ncheck-regexes: %d of %d patterns would fail on a phone.'
              % (len(failures), checked), file=sys.stderr)
        return 1

    print('check-regexes: %d patterns compile on ICU, the engine Android uses.' % checked)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:] or ['core/src/main', 'app/src/main']))
