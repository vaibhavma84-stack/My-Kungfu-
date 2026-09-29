#!/usr/bin/env bash
# Copies the app's runtime files into docs/money/, which is what GitHub Pages
# serves for this repository (Pages is set to main, folder /docs).
#
# Only the six files the phone needs. The tests, the Android project and its
# keystore stay out of the published folder — there is no reason for a browser
# to be able to fetch them.
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../docs/money"
mkdir -p "$out"
for f in index.html sw.js manifest.webmanifest icon-180.png icon-192.png icon-512.png; do
  cp "$here/$f" "$out/$f"
done
echo "published $(ls -1 "$out" | wc -l) files to docs/money/"
