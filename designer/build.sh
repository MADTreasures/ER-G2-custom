#!/bin/sh
# Builds index.html (a complete page for opening locally) from baukasten.html, the artifact source.
# The reset mirrors the skeleton claude.ai wraps around a published artifact.
set -eu
cd "$(dirname "$0")"
{
  printf '%s\n' '<!doctype html>' '<html lang="de">' '<head>' '<meta charset="utf-8">' \
    '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">' \
    '<style>' \
    ':root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}' \
    'body{margin:0;font:14px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:#f8f8f6}' \
    'img{max-width:100%}' \
    '[hidden]{display:none!important}' \
    '</style>' '</head>' '<body>'
  cat baukasten.html
  printf '%s\n' '</body>' '</html>'
} > index.html.tmp
mv index.html.tmp index.html
echo "index.html geschrieben ($(wc -c < index.html) Bytes)"
