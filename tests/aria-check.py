# Icon-only buttons (the trash can, the camera, the pencil) announce nothing
# to a screen reader without their own aria-label -- a hover title does
# nothing on a phone, which is the only place this app actually runs. This
# checks every icon-btn in the source carries one, rather than trusting that
# whichever ones happen to get clicked in a browser test are the only ones
# that exist: a button added later without one would pass every existing
# test and still be silent to VoiceOver or TalkBack.
import io, re, sys

s = io.open('/home/user/expenses/GasPlanet_ToDoList.html', encoding='utf-8').read()

# [^>]* cannot itself consume a '>', so each match stops at the tag's own
# closing bracket regardless of how many icon-btn tags are concatenated on
# the same line -- no risk of one match swallowing the next.
tags = re.findall(r'<button\b[^>]*class="icon-btn"[^>]*>', s)
missing = [t for t in tags if 'aria-label=' not in t]

print('checked: %d icon-btn tags' % len(tags))
if missing:
    print()
    for t in missing: print('  MISSING aria-label  ' + t[:140])
    print('\n%d PROBLEM(S)' % len(missing))
    sys.exit(1)
print('aria labels OK')
