#!/usr/bin/env python3
"""Write tests/sample.pdf.

The notes suite needs a PDF with real text in it -- something the highlighter
can snap onto -- and nothing in this project is fetched from anywhere. So it is
written by hand: a catalogue, a page tree, one content stream per page and a
cross-reference table saying where each object starts. That is the whole format
when no compression and no embedded fonts are involved.

    python3 tests/make-sample-pdf.py
"""
import os

PAGES = [
    [(22, 'PRE ARRIVAL CHECKLIST'),
     (13, 'Test mooring winch brakes'),
     (13, 'Sound all cargo tank pressures'),
     (13, 'Emergency shutdown tested')],
    [(22, 'LAST MINUTE CHECKS'),
     (13, 'Accommodation at positive pressure'),
     (13, 'Drip trays and scuppers plugged')],
]


def build(pages):
    out = bytearray()
    offsets = {}

    def put(b):
        out.extend(b if isinstance(b, (bytes, bytearray)) else b.encode('latin-1'))

    def obj(num, body, stream=None):
        offsets[num] = len(out)
        put('%d 0 obj\n%s\n' % (num, body))
        if stream is not None:
            put('stream\n'); put(stream); put('\nendstream\n')
        put('endobj\n')

    put('%PDF-1.4\n')
    kids = ' '.join('%d 0 R' % (3 + i * 2) for i in range(len(pages)))
    obj(1, '<< /Type /Catalog /Pages 2 0 R >>')
    obj(2, '<< /Type /Pages /Kids [%s] /Count %d >>' % (kids, len(pages)))
    font_no = 3 + len(pages) * 2
    for i, lines in enumerate(pages):
        page_no, cont_no = 3 + i * 2, 4 + i * 2
        obj(page_no, '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] '
                     '/Resources << /Font << /F1 %d 0 R >> >> /Contents %d 0 R >>'
                     % (font_no, cont_no))
        cs, y = '', 760
        for size, txt in lines:
            cs += 'BT /F1 %d Tf 72 %d Td (%s) Tj ET\n' % (size, y, txt)
            y -= int(size * 2.0)
        obj(cont_no, '<< /Length %d >>' % len(cs), cs)
    obj(font_no, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')

    total = font_no
    xref = len(out)
    t = 'xref\n0 %d\n0000000000 65535 f \n' % (total + 1)
    for k in range(1, total + 1):
        t += '%010d 00000 n \n' % offsets[k]
    t += ('trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n'
          % (total + 1, xref))
    put(t)
    return bytes(out)


if __name__ == '__main__':
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sample.pdf')
    data = build(PAGES)
    open(path, 'wb').write(data)
    print('%s  %d bytes' % (path, len(data)))
