#!/usr/bin/env python3
"""
Android resource sanity, without an Android SDK.

There is no SDK in the environment this app is developed in, so CI is the only
compiler and every mistake costs a full build cycle. This catches the classes of
error that cost one: an unescaped apostrophe in a string resource (a hard aapt
error, and the one that broke the v39 build), a reference to a resource that
does not exist, and malformed XML.

    python3 check-resources.py
"""
import os, re, sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'app', 'src', 'main')
RES  = os.path.join(ROOT, 'res')
JAVA = os.path.join(ROOT, 'java')

fails = []
def fail(msg): fails.append(msg)

def res_files(*subdirs):
    out = []
    for d in subdirs:
        p = os.path.join(RES, d)
        if not os.path.isdir(p): continue
        for f in sorted(os.listdir(p)):
            if f.endswith('.xml'): out.append(os.path.join(p, f))
    return out

# ---- 1. every XML file parses -------------------------------------------
all_xml = res_files('layout', 'values', 'xml', 'drawable') + \
          [os.path.join(ROOT, 'AndroidManifest.xml')]
for f in all_xml:
    try:
        ET.parse(f)
    except Exception as e:
        fail('MALFORMED XML  %s: %s' % (os.path.relpath(f, ROOT), e))

# ---- 2. string resources: apostrophes and quotes must be escaped ---------
# aapt rejects a bare ' in a string body outright.
for f in res_files('values'):
    try: tree = ET.parse(f)
    except Exception: continue
    for el in tree.getroot():
        if el.tag != 'string': continue
        body = el.text or ''
        # a bare apostrophe not preceded by a backslash
        if re.search(r"(?<!\\)'", body):
            fail('UNESCAPED APOSTROPHE  %s in <string name="%s"> -- write \\\' '
                 % (os.path.relpath(f, ROOT), el.get('name')))
        if re.search(r'(?<!\\)"', body):
            fail('UNESCAPED QUOTE  %s in <string name="%s">'
                 % (os.path.relpath(f, ROOT), el.get('name')))

# ---- 3. collect what exists ---------------------------------------------
defined_ids = set()
for f in res_files('layout'):
    defined_ids |= set(re.findall(r'@\+id/(\w+)', open(f, encoding='utf-8').read()))

layouts   = {os.path.splitext(os.path.basename(f))[0] for f in res_files('layout')}
drawables = {os.path.splitext(os.path.basename(f))[0] for f in res_files('drawable')}
xmls      = {os.path.splitext(os.path.basename(f))[0] for f in res_files('xml')}
strings, styles = set(), set()
for f in res_files('values'):
    try: root = ET.parse(f).getroot()
    except Exception: continue
    for el in root:
        if el.tag == 'string' and el.get('name'): strings.add(el.get('name'))
        if el.tag == 'style' and el.get('name'): styles.add(el.get('name'))

BUCKETS = {'id':defined_ids, 'layout':layouts, 'drawable':drawables,
           'xml':xmls, 'string':strings, 'style':styles}

# ---- 4. every @reference in res/ and the manifest resolves ---------------
for f in all_xml:
    txt = open(f, encoding='utf-8').read()
    rel = os.path.relpath(f, ROOT)
    for kind, name in re.findall(r'@(?!\+)(\w+)/([\w.]+)', txt):
        if kind in ('android', 'mipmap'): continue      # framework / not checked here
        bucket = BUCKETS.get(kind)
        if bucket is None: continue
        if name not in bucket:
            fail('MISSING @%s/%s  referenced by %s' % (kind, name, rel))

# ---- 5. every R.* the Kotlin uses exists --------------------------------
for dirpath, _, files in os.walk(JAVA):
    for fn in files:
        if not fn.endswith('.kt'): continue
        f = os.path.join(dirpath, fn)
        txt = open(f, encoding='utf-8').read()
        for kind, name in re.findall(r'\bR\.(\w+)\.(\w+)', txt):
            bucket = BUCKETS.get(kind)
            if bucket is None: continue
            if name not in bucket:
                fail('MISSING R.%s.%s  used by %s' % (kind, name, fn))

# ---- 6. every provider named in the manifest has a class ----------------
man = open(os.path.join(ROOT, 'AndroidManifest.xml'), encoding='utf-8').read()
for cls in re.findall(r'android:name="\.(\w+)"', man):
    hit = False
    for dirpath, _, files in os.walk(JAVA):
        if cls + '.kt' in files: hit = True
    if not hit:
        fail('MANIFEST names .%s but there is no %s.kt' % (cls, cls))

print('checked: %d xml files, %d ids, %d layouts, %d strings, %d styles'
      % (len(all_xml), len(defined_ids), len(layouts), len(strings), len(styles)))
if fails:
    print()
    for m in fails: print('  ' + m)
    print('\n%d PROBLEM(S)' % len(fails))
    sys.exit(1)
print('resources OK')
