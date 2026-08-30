#!/usr/bin/env python3
"""Check what the release actually serves, not what we think we pushed.

A build can fail after a green-looking push, and the release link then keeps
serving the previous APK. Telling someone a version is ready without checking
wastes a download and a reinstall at sea, so this reads the published file.

    python3 verify-release.py v33
"""
import re, subprocess, sys, tempfile, zipfile, os

URL = 'https://github.com/vaibhavma84-stack/My-Kungfu-/releases/latest/download/DeckLog.apk'

want = sys.argv[1] if len(sys.argv) > 1 else None
tmp = os.path.join(tempfile.mkdtemp(), 'DeckLog.apk')
r = subprocess.run(['curl', '-sSL', '-o', tmp, URL], capture_output=True, text=True)
if r.returncode != 0:
    sys.exit('verify-release: could not download the release APK\n' + r.stderr)

with zipfile.ZipFile(tmp) as z:
    page = z.read('assets/index.html').decode('utf-8', 'replace')
m = re.search(r"APP_BUILD = '([^']+)'", page)
got = m.group(1) if m else None
size = os.path.getsize(tmp)
print('published APK: %s  (%.1f MB)' % (got, size / 1048576))

if want and got != want:
    sys.exit('\nverify-release: the release serves %s, not %s.\n'
             'The build for %s did not publish — check the workflow run before\n'
             'telling anyone it is ready.' % (got, want, want))
print('verify-release: OK')
