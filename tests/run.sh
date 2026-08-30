#!/usr/bin/env bash
# Runs every suite against index.html.
#
#   npm install playwright-core          (once)
#   APP_HTML=../index.html ./run.sh      (from tests/)
#
# Needs a Chromium binary. Point CHROME at it if it is not where the suites
# expect: /opt/pw-browsers/chromium-1194/chrome-linux/chrome
set -u
export APP_HTML="${APP_HTML:-$(cd "$(dirname "$0")/.." && pwd)/index.html}"
export OUT="${OUT:-$(mktemp -d)}"
cp "$(dirname "$0")/pic.jpg" "$OUT/" 2>/dev/null
cp "$(dirname "$0")/sample-import.csv" "$OUT/import_todo.csv" 2>/dev/null

fail=0
for t in "$(dirname "$0")"/*-test.js "$(dirname "$0")"/collapse.js; do
  [ -f "$t" ] || continue
  printf '%-22s ' "$(basename "$t" .js)"
  if out=$(node "$t" 2>&1); then echo "${out##*$'\n'}"; else echo "FAILED"; echo "$out" | tail -20; fail=1; fi
done
printf '%-22s ' "guide-check"
if out=$(python3 "$(dirname "$0")/guide-check.py" 2>&1); then echo "${out##*$'\n'}"; else echo "FAILED"; echo "$out" | grep FAIL | head -5; fail=1; fi
printf '%-22s ' "factor-check"
if out=$(python3 "$(dirname "$0")/factor-check.py" 2>&1); then echo "${out##*$'\n'}"; else echo "FAILED"; echo "$out" | head -8; fail=1; fi
printf '%-22s ' "instrument-files"
if out=$(python3 "$(dirname "$0")/instrument-files-check.py" 2>&1); then echo "${out##*$'\n'}"; else echo "FAILED"; echo "$out" | grep FAIL | head -5; fail=1; fi

echo
[ $fail -eq 0 ] && echo "all suites passed" || echo "something failed"
exit $fail
