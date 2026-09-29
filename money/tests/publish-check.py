#!/usr/bin/env python3
"""The published copy must match the source.

GitHub Pages serves docs/ for this repository, so docs/money/ is what actually
reaches the phone. money/ is where the app is edited. Two copies of a file is
two copies that can drift, and the way that goes wrong is silent: the tests all
pass against money/index.html while the phone keeps running last week's build,
and nothing on either side says so.

    python3 publish-check.py          # run by money/tests/run.sh
    money/publish.sh                  # fixes it
"""
import hashlib, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC  = os.path.normpath(os.path.join(HERE, '..'))
OUT  = os.path.normpath(os.path.join(HERE, '..', '..', 'docs', 'money'))
FILES = ['index.html', 'sw.js', 'manifest.webmanifest',
         'icon-180.png', 'icon-192.png', 'icon-512.png']

def sha(path):
    with open(path, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()

fails = []
for name in FILES:
    a, b = os.path.join(SRC, name), os.path.join(OUT, name)
    if not os.path.exists(a):
        fails.append('MISSING SOURCE    money/%s' % name); continue
    if not os.path.exists(b):
        fails.append('NOT PUBLISHED     docs/money/%s' % name); continue
    if sha(a) != sha(b):
        fails.append('DRIFTED           docs/money/%s is not money/%s' % (name, name))

# Nothing else belongs in the published folder — least of all the signing key.
extra = sorted(set(os.listdir(OUT)) - set(FILES)) if os.path.isdir(OUT) else []
for name in extra:
    fails.append('SHOULD NOT BE PUBLISHED  docs/money/%s' % name)

print('checked: %d files' % len(FILES))
if fails:
    print()
    for m in fails:
        print('  ' + m)
    print('\n%d PROBLEM(S) — run money/publish.sh' % len(fails))
    sys.exit(1)
print('published copy matches the source')
