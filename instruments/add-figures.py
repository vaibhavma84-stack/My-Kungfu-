# -*- coding: utf-8 -*-
"""Attach the manuals' own figures to the steps they illustrate.

The figures are the raster images embedded in the PDFs the vessel supplied, so
they are the maker's drawings rather than anything redrawn. Each is matched to a
step by the manual page it came from, which is also what the step cites.
"""
import base64, io, json
from PIL import Image

FIG = 'figs/%s'

def encode(name, cap=1100):
    img = Image.open(FIG % name)
    w, h = img.size
    sc = min(1.0, float(cap) / max(w, h))
    img = img.convert('L').resize((max(1,int(w*sc)), max(1,int(h*sc))), Image.LANCZOS)
    # line art: a small palette keeps the strokes crisp and the file a third the
    # size of a JPEG, which would ring around every rule
    img = img.convert('P', palette=Image.ADAPTIVE, colors=16)
    b = io.BytesIO(); img.save(b, format='PNG', optimize=True)
    return 'data:image/png;base64,' + base64.b64encode(b.getvalue()).decode()

# procedure title fragment -> [(step text fragment, figure)]
PLACE = {
 'Bump test': [
   ('keep ▼ pressed and press the DISPLAY switch', 'MN_p05_0.png'),
   ('Press ▲ or ▼ until [BUMP] is displayed',       'MN_p07_0.png'),
   ('IF ALL CHANNELS PASS',                          'MN_p07_1.png'),
   ('IF ANY CHANNEL FAILS',                          'MN_p08_0.png'),
 ],
 'Change an alarm setpoint': [
   ('keep ▲ and ▼ pressed and press the POWER switch', 'MN_p09_0.png'),
   ('Password: 0008',                                   'MN_p09_1.png'),
   ('Press ▲ or ▼ until [ALARM-P] is displayed',        'MN_p10_0.png'),
   ('The alarm setpoint setting selection menu',        'MN_p12_0.png'),
   ('press ▲ or ▼ until [START]',                       'MN_p10_1.png'),
 ],
 'Fresh air adjustment': [
   ('press and keep pressing the AIR switch',  'OP_p31_1.png'),
   ('When [RELEASE] is displayed',             'MN_p17_0.png'),
   ('IF IT FAILS',                             'OP_p32_0.png'),
 ],
 'Span adjustment — all channels at once': [
   ('Connect the gas sampling bag to the probe and GAS IN', 'MN_p14_0.png'),
   ('Press ▲ or ▼ until [AUTO CAL] is displayed',           'MN_p18_0.png'),
 ],
 'Span adjustment — one channel': [
   ('Press ▲ or ▼ until [ONE CAL] is displayed', 'MN_p19_0.png'),
   ('Repeat for the other gases',                 'MN_p19_1.png'),
 ],
 'Bump test settings': [
   ('Press ▲ or ▼ until [BUMP-SET] is displayed', 'MN_p13_0.png'),
 ],
 'Combustible range setting': [
   ('Press the DISPLAY switch and select the combustible', 'OP_p35_1.png'),
   ('Each press of ▲ or ▼ steps through',                  'OP_p35_2.png'),
   ('CAUTION — NO GAS ALARM IS TRIGGERED',                 'OP_p36_1.png'),
 ],
 'Zero adjustment, high-concentration combustible': [
   ('Press ▲ or ▼ until [VOL Z.CAL] is displayed', 'MN_p20_0.png'),
 ],
 'Monthly alarm test — display only': [
   ('Press the DISPLAY switch and select "full scale', 'OP_p33_0.png'),
   ('Press ▲ or ▼ to step through',                    'OP_p37_3.png'),
 ],
 'Activated carbon filter replacement': [
   ('turn the activated carbon filter knob counterclockwise', 'OP_p47_1.png'),
   ('Pull out the filter case',                                'OP_p47_2.png'),
   ('Replace the two activated carbon filters',                'OP_p47_3.png'),
 ],
 'Gas sampling probe filter replacement': [
   ('Rotate the end of the probe counterclockwise', 'OP_p46_1.png'),
 ],
}

path = '/home/user/expenses/instruments/GX-8000.json'
doc = json.load(io.open(path, encoding='utf-8'))
inst = doc['instruments'][0]

placed = 0
missing = []
# start clean, so re-running never doubles the figures up
for pr in inst['procedures']:
    for st in pr['steps']:
        st.pop('photos', None)

heads = {}
for pr in inst['procedures']:
    heads[pr['title'].split('  [')[0].strip().lower()] = pr

unknown = [k for k in PLACE if k.strip().lower() not in heads]
if unknown:
    raise SystemExit('add-figs: these keys match no procedure: %s' % unknown)

for key, pairs in PLACE.items():
    pr = heads[key.strip().lower()]
    if True:
        for frag, fig in pairs:
            hit = None
            for st in pr['steps']:
                if frag.lower() in st['text'].lower():
                    hit = st; break
            if not hit:
                missing.append((pr['title'][:40], frag[:40]))
                continue
            hit.setdefault('photos', []).append(encode(fig))
            placed += 1

doc['source'] += (' Figures are the drawings embedded in those PDFs, matched to '
                  'the step by the manual page each step cites.')
io.open(path, 'w', encoding='utf-8').write(json.dumps(doc, indent=2, ensure_ascii=False))
print('figures placed:', placed)
if missing:
    print('COULD NOT PLACE (step text not found):')
    for m in missing: print('   ', m)
    raise SystemExit('add-figs: %d figure(s) had nowhere to go — fix the match '
                     'rather than shipping a procedure with a picture missing'
                     % len(missing))
import os
print('file size: %.2f MB' % (os.path.getsize(path)/1048576))
