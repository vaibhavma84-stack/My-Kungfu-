#!/usr/bin/env python3
"""Check what the release actually serves, not what we think we pushed.

A build can fail after a green-looking push, and the release link then keeps
serving the previous APK. Telling someone a version is ready without checking
wastes a download and a reinstall at sea, so this reads the published file.

    python3 verify-release.py v33

The build string alone is NOT enough. Two commits pushed under the same
APP_BUILD produce two APKs that both answer "v42", and the first build to
finish wins the release. That happened: a widget fix was pushed after the
version bump, and the published v42 was the commit before it. So this also
reads the commit the APK was built from and, when it can, checks that it is
the commit currently at HEAD.
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
    try:
        built_sha = z.read('assets/BUILD_SHA').decode().strip()
    except KeyError:
        built_sha = None
m = re.search(r"APP_BUILD = '([^']+)'", page)
got = m.group(1) if m else None
size = os.path.getsize(tmp)
print('published APK: %s  (%.1f MB)' % (got, size / 1048576))

head = subprocess.run(['git', 'rev-parse', 'HEAD'], capture_output=True, text=True,
                      cwd=os.path.dirname(os.path.abspath(__file__)))
head_sha = head.stdout.strip() if head.returncode == 0 else None
if built_sha:
    print('built from commit: %s%s' % (built_sha[:8],
          '' if not head_sha else ('  (HEAD)' if built_sha == head_sha else '  <-- HEAD is ' + head_sha[:8])))
    if head_sha and built_sha != head_sha:
        sys.exit('\nverify-release: this APK was built from %s, but HEAD is %s.\n'
                 'A later push has not published yet. Wait for its build before\n'
                 'telling anyone it is ready.' % (built_sha[:8], head_sha[:8]))
else:
    print('built from commit: not recorded in this APK')

if want and got != want:
    sys.exit('\nverify-release: the release serves %s, not %s.\n'
             'The build for %s did not publish — check the workflow run before\n'
             'telling anyone it is ready.' % (got, want, want))
print('verify-release: OK')
