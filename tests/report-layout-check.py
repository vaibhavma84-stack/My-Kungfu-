#!/usr/bin/env python3
"""Check the printed weekly report page by page.

The layout rules only exist on paper — on screen everything is one long
scroll — so they can only be checked by producing a real PDF and reading it
back. Run by report-layout-test.js, which generates the PDF first.

    python3 report-layout-check.py <report.pdf>
"""
import re, sys
try:
    from pypdf import PdfReader
except Exception as e:                      # pragma: no cover - environment only
    print('  SKIP  PDF layout checks — pypdf unavailable (%s).' % e.__class__.__name__)
    print('        pip install pypdf cffi')
    sys.exit(0)

TITLE = 'WEEKLY WORK DONE REPORT'

fails = []
def ok(name, cond, extra=''):
    print(('  PASS  ' if cond else '  FAIL  ') + name + ('' if cond else '  -> ' + str(extra)))
    if not cond: fails.append(name)

reader = PdfReader(sys.argv[1])
pages  = [p.extract_text() or '' for p in reader.pages]
print('        %d pages' % len(pages))

ok('the report runs to more than one page', len(pages) > 1, len(pages))

# 1. the header repeats
missing = [i + 1 for i, t in enumerate(pages) if TITLE not in t]
ok('the header is on every page', not missing, 'missing on page(s) %s' % missing)

weekless = [i + 1 for i, t in enumerate(pages) if ' to ' not in t]
ok('the week range is on every page', not weekless, 'missing on page(s) %s' % weekless)

# 2. page one carries the statements and no photographs.
#    A photo page is identified by its captions; the caption band repeats the
#    job name under each pair, so a page with photos repeats a job name.
def images_on(page):
    """How many images are *drawn* on this page.

    Counting the image objects in the page resources is not the same thing and
    quietly gives the wrong answer: identical photographs are stored once and
    referenced many times, so four copies of one picture look like one image.
    What has to be counted is the draw operations in the content stream.
    """
    res = page.get('/Resources')
    if res is None:
        return 0
    xo = res.get('/XObject')
    if xo is None:
        return 0
    xo = xo.get_object()
    names = [k for k in xo if xo[k].get_object().get('/Subtype') == '/Image']
    if not names:
        return 0
    try:
        content = page.get_contents().get_data().decode('latin1')
    except Exception:
        return 0
    # "/Im0 Do" places one image; the same name may appear many times
    return sum(len(re.findall(r'/%s\s+Do\b' % re.escape(n.lstrip('/')), content))
               for n in names)

counts = [images_on(p) for p in reader.pages]
print('        images drawn per page: %s  (the letterhead logo is 1 of each)' % counts)

# the logo is an image too and repeats with the header, so a photo-free page
# carries exactly one image
ok('page one has no photographs', counts[0] <= 1, '%d images on page 1' % counts[0])

photo_pages = counts[1:]
ok('there are photograph pages', len(photo_pages) > 0 and max(photo_pages or [0]) > 1,
   counts)

# 3. at most four photographs to a page (plus the one logo)
over = [(i + 2, c - 1) for i, c in enumerate(photo_pages) if c - 1 > 4]
ok('never more than four photographs on a page', not over, over)

# 4. every page carries its own number, and they run in order
numbers = []
for i, t in enumerate(pages):
    m = re.search(r'Page\s+(\d+)\s+of\s+(\d+)', t)
    numbers.append((int(m.group(1)), int(m.group(2))) if m else None)
ok('every page carries a page number', all(numbers), numbers)
if all(numbers):
    ok('the numbers run 1..n in order',
       [n[0] for n in numbers] == list(range(1, len(pages) + 1)), [n[0] for n in numbers])
    ok('the total on each page is the real total',
       all(n[1] == len(pages) for n in numbers), numbers)

# 5. no blank trailing page
ok('the last page is not blank', pages[-1].strip() != '' or counts[-1] > 1,
   'last page empty')

print('\nALL PASS' if not fails else '\n%d FAILED' % len(fails))
sys.exit(1 if fails else 0)
