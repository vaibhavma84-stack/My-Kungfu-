#!/usr/bin/env bash
# Runs the Ledger suite against money/index.html.
#
#   (cd ../../tests && npm install playwright-core)     # once, shared
#   ./run.sh
#
# Needs a Chromium binary. Point CHROME at it if it is not where the suite
# expects: /opt/pw-browsers/chromium-1194/chrome-linux/chrome
set -u
here="$(cd "$(dirname "$0")" && pwd)"
export APP_HTML="${APP_HTML:-$here/../index.html}"
export NODE_PATH="${NODE_PATH:-$here/../../tests/node_modules}"

fail=0
for t in "$here"/*-test.js; do
  [ -f "$t" ] || continue
  printf '%-22s ' "$(basename "$t" .js)"
  if out=$(node "$t" 2>&1); then echo "${out##*$'\n'}"; else echo "FAILED"; echo "$out" | grep FAIL | head -20; fail=1; fi
done

echo
[ $fail -eq 0 ] && echo "all suites passed" || echo "something failed"
exit $fail
