# Cross-check each factor in the app against an independently stated reference
# value, not against another factor from the same table.
import io, re, json, sys

s = io.open('/home/user/expenses/GasPlanet_ToDoList.html', encoding='utf-8').read()
block = s[s.index('var CONV_CATS = ['):s.index('var CONV_KEY')]

cats = {}
for cm in re.finditer(r"\{ key:'(\w+)'.*?units:\[(.*?)\]\s*\}", block, re.S):
    key, body = cm.group(1), cm.group(2)
    units = {}
    for um in re.finditer(r"\['([^']+)',\s*'([^']*)',\s*([0-9.eE+-]+)\]", body):
        units[um.group(1)] = float(um.group(3))
    cats[key] = units

# reference: unit -> how many base units it is, from published definitions
REF = {
 'pressure': {  # pascals
   'bar':1e5, 'mbar':1e2, 'Pa':1.0, 'kPa':1e3, 'MPa':1e6, 'hPa':1e2,
   'kgcm2':9.80665*10000,          # 1 kgf over 1 cm2 = 9.80665 N / 1e-4 m2
   'psi':4.4482216152605/(0.0254**2),   # lbf / in2
   'atm':101325.0,
   'mmHg':13595.1*9.80665*0.001,   # Hg density at 0C x g x 1mm
   'inHg':13595.1*9.80665*0.0254,
   'mmH2O':1000*9.80665*0.001,     # water x g x 1mm
   'mH2O':1000*9.80665*1.0,
   'inH2O':1000*9.80665*0.0254,
 },
 'energy': {  # joules
   'J':1.0,'kJ':1e3,'MJ':1e6,'GJ':1e9,
   'kWh':1000*3600,'MWh':1e6*3600,
   'cal':4.184,'kcal':4184.0,
   'BTU':1055.05585262,            # IT BTU, defined
   'MMBTU':1055.05585262*1e6,
   'therm':1055.05585262*1e5,      # 100 000 BTU
   'ftlbf':4.4482216152605*0.3048,
 },
 'volume': {  # m3
   'm3':1.0,'L':1e-3,'mL':1e-6,
   'ft3':0.3048**3,'in3':0.0254**3,
   'galUS':231*(0.0254**3),        # 231 cubic inches, defined
   'galUK':4.54609e-3,             # defined
   'bbl':42*231*(0.0254**3),
 },
 'mass': {  # kg
   'kg':1.0,'g':1e-3,'t':1e3,
   'lb':0.45359237,'oz':0.45359237/16,
   'tonLong':2240*0.45359237,'tonShort':2000*0.45359237,
 },
 'length': {  # m
   'm':1.0,'mm':1e-3,'cm':1e-2,'km':1e3,
   'in':0.0254,'ft':0.3048,'yd':0.9144,
   'fath':6*0.3048,'nm':1852.0,'mi':5280*0.3048,
 },
 'density': {  # kg/m3
   'kgm3':1.0,'kgL':1e3,'gcm3':1e3,'tm3':1e3,
   'lbft3':0.45359237/(0.3048**3),
   'lbgal':0.45359237/(231*(0.0254**3)),
 },
 'flow': {  # m3/h
   'm3h':1.0,'m3min':60.0,'m3s':3600.0,
   'Lmin':1e-3*60,'Ls':1e-3*3600,
   'gpm':231*(0.0254**3)*60,
   'ft3h':0.3048**3,
   'bblh':42*231*(0.0254**3),
 },
 'speed': {  # m/s
   'kn':1852/3600,'ms':1.0,'kmh':1000/3600,'mph':1609.344/3600,'fts':0.3048,
 },
}

bad = 0
for cat, ref in REF.items():
    got = cats.get(cat, {})
    missing = set(ref) ^ set(got)
    if missing:
        print('%-10s UNIT SET MISMATCH: %s' % (cat, sorted(missing))); bad += 1
    for u, want in ref.items():
        have = got.get(u)
        if have is None: continue
        rel = abs(have - want) / abs(want)
        if rel > 1e-9:
            print('%-10s %-8s app=%r ref=%r  rel err %.3g' % (cat, u, have, want, rel)); bad += 1

print()
print('factors checked:', sum(len(v) for v in REF.values()))
print('MISMATCHES:', bad)
sys.exit(1 if bad else 0)
