#!/usr/bin/env python3
"""Structural check on every file in instruments/.

These are transcriptions of safety documents. A malformed file, a step with no
citation, or a setpoint table quietly reduced to one variant would all import
without complaint and read as authoritative, so they are checked here.
"""
import glob, json, os, re, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'instruments')
fails = []
def ok(name, cond, extra=''):
    print(('  PASS  ' if cond else '  FAIL  ') + name + ('' if cond else '  -> ' + str(extra)))
    if not cond: fails.append(name)

files = sorted(glob.glob(os.path.join(ROOT, '*.json')))
ok('there are instrument files', bool(files), ROOT)

for path in files:
    name = os.path.basename(path)
    try:
        doc = json.load(open(path, encoding='utf-8'))
    except Exception as e:
        ok('%s parses' % name, False, e); continue
    ok('%s parses' % name, True)
    ok('%s declares the import format' % name,
       doc.get('format') == 'gasplanet-instruments', doc.get('format'))
    ok('%s names its sources' % name, bool(doc.get('source')), doc.get('source'))

    for inst in doc.get('instruments', []):
        m = inst.get('model', '?')
        ok('%s has procedures' % m, len(inst.get('procedures', [])) > 0)
        ok('%s has a spares list' % m, len(inst.get('parts', [])) > 0)

        steps = [s for p in inst['procedures'] for s in p['steps']]
        # every step must say where it came from
        uncited = [s['text'][:60] for s in steps if not re.search(r'\[[A-Z0-9]', s['text'])]
        ok('%s: every step cites a document' % m, not uncited, uncited[:3])

        titles = [p['title'] for p in inst['procedures']]
        ok('%s: every procedure title cites a document' % m,
           all(re.search(r'\[[A-Z0-9]', t) for t in titles),
           [t for t in titles if not re.search(r'\[[A-Z0-9]', t)][:2])

        body = inst.get('notes', '') + ' '.join(s['text'] for s in steps)
        # Where the manual publishes factory setpoints it gives two tables, ATEX
        # and TIIS, whose oxygen values differ - keeping only one is wrong half
        # the time. Where it publishes none, the file must say so outright
        # rather than leave a reader assuming the instrument alarms.
        both_tables = '19.5' in body and '18 vol%' in body
        says_none = 'GAS ALARM IS AN OPTIONAL SETTING' in body or 'has NO gas alarm' in body
        ok('%s: setpoints either give both tables or state there are none' % m,
           both_tables or says_none,
           'neither the ATEX/TIIS pair nor an explicit "no published setpoints" statement')
        # the arrow keys must be arrows, never the letters the PDF extracts to
        ok('%s: no mis-read S/T switch names' % m,
           not re.search(r'\b(the )?[ST] (switch|switches)\b', body),
           re.findall(r'.{20}\b[ST] switch\w*', body)[:2])
        ok('%s: arrow keys present' % m, '▲' in body and '▼' in body)
        # a bump interval nobody wrote down must not be invented
        ok('%s: bump interval left unset' % m, not inst.get('bumpDays'), inst.get('bumpDays'))

print('\nALL PASS' if not fails else '\n%d FAILED' % len(fails))
sys.exit(1 if fails else 0)
