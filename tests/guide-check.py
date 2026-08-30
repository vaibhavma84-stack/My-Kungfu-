# The Information tool draws its entries from the converter's own unit lists
# plus a few topic categories of its own, and its text from two separate note
# objects. Nothing in the app forces those to agree: a unit added to the
# converter with no note written for it shows an entry with an empty body, and
# a note whose key was mistyped shows nothing at all. Neither is visible in a
# screenshot. This asserts they stay in step.
import io, re, json, sys

s = io.open('/home/user/expenses/GasPlanet_ToDoList.html', encoding='utf-8').read()

def obj_after(marker):
    """The JSON literal assigned at `marker`, read by matching braces."""
    i = s.index(marker) + len(marker)
    i = s.index('{', i)
    d, j = 0, i
    while True:
        if s[j] == '{': d += 1
        elif s[j] == '}':
            d -= 1
            if d == 0: return json.loads(s[i:j+1])
        elif s[j] == '"':                      # skip over string bodies
            j += 1
            while s[j] != '"':
                j += 2 if s[j] == '\\' else 1
        j += 1

CAT_NOTES  = obj_after('var CAT_NOTES  =') if 'var CAT_NOTES  =' in s else obj_after('var CAT_NOTES =')
UNIT_NOTES = obj_after('var UNIT_NOTES =')

# the two late assignments, added for the topic categories
for m in re.finditer(r"CAT_NOTES\.(\w+)\s*=\s*(\"(?:[^\"\\]|\\.)*\")", s):
    CAT_NOTES[m.group(1)] = json.loads(m.group(2))
for m in re.finditer(r"UNIT_NOTES\.(\w+)\s*=\s*", s):
    UNIT_NOTES[m.group(1)] = obj_after(m.group(0))

# every category the guide shows, and the entry keys under each
block = s[s.index('var CONV_CATS = ['):s.index('var guideCat')]
cats = []
for cm in re.finditer(r"\{ key:'(\w+)',\s*name:'([^']+)'.*?units:\[(.*?)\]\s*\}", block, re.S):
    keys = [um.group(1) for um in re.finditer(r"\['([^']+)',", cm.group(3))]
    cats.append((cm.group(1), cm.group(2), keys))

if len(cats) < 10:
    sys.exit('PARSE FAILED: only %d categories read' % len(cats))

bad = 0
for key, name, keys in cats:
    if not CAT_NOTES.get(key, '').strip():
        print('FAIL  category %-12s has no intro note' % key); bad += 1
    notes = UNIT_NOTES.get(key, {})
    for u in keys:
        if not notes.get(u, '').strip():
            print('FAIL  %-12s %-10s entry has no note' % (key, u)); bad += 1
    for u in notes:
        if u not in keys:
            print('FAIL  %-12s %-10s note written for an entry that is not shown' % (key, u)); bad += 1
for key in UNIT_NOTES:
    if key not in [c[0] for c in cats]:
        print('FAIL  notes for category %s, which the guide does not show' % key); bad += 1

print()
print('categories: %d   entries: %d' % (len(cats), sum(len(c[2]) for c in cats)))
print('FAILURES:', bad)
sys.exit(1 if bad else 0)
