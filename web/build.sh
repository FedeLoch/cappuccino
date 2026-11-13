#!/usr/bin/env bash
# Builds the web version into docs/app/ as plain static files.
# Needs Emscripten on the PATH (brew install emscripten).
set -euo pipefail

cd "$(dirname "$0")/.."
ENGINE=engine/src
OUT=docs/app
MODE="${1:-release}"

if ! command -v em++ >/dev/null; then
    echo "em++ not found. Install Emscripten: brew install emscripten" >&2
    exit 1
fi

if [ "$MODE" = debug ]; then
    OPT=(-O1 -g -sASSERTIONS=2 -sGL_ASSERTIONS=1)
else
    OPT=(-O3 -flto)
fi

mkdir -p "$OUT"

em++ "${OPT[@]}" -std=c++20 -Wall -Wextra \
    -I"$ENGINE" \
    web/src/web_api.cpp \
    "$ENGINE/fill.cpp" "$ENGINE/liquify.cpp" "$ENGINE/model3d.cpp" "$ENGINE/renderer.cpp" "$ENGINE/stroke.cpp" \
    -sMIN_WEBGL_VERSION=2 -sMAX_WEBGL_VERSION=2 \
    -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=64MB -sMAXIMUM_MEMORY=2GB \
    -sMODULARIZE=1 -sEXPORT_ES6=1 -sEXPORT_NAME=createEngine -sENVIRONMENT=web \
    -sEXPORTED_RUNTIME_METHODS=HEAPF32,HEAPU8,HEAP32 -sEXPORTED_FUNCTIONS=_malloc,_free \
    -sSTACK_SIZE=1MB \
    -o "$OUT/engine.js"

python3 web/tools/icons.py app/src/main/res/drawable "$OUT/icons.js"
python3 web/tools/translations.py app/src/main/java/art/cappuccino/paint/Translations.kt "$OUT/translations.js"
cp web/index.html web/style.css web/app.js web/brushes.js web/companion.js web/ui.js web/home.js web/storage.js web/analysis.js web/warmups.js web/practice.js web/psd.js web/references.js web/match.js web/zip.js web/i18n.js web/shapes.js web/print.js web/lettering.js web/figure.js "$OUT/"
echo "Built $OUT: $(du -sh "$OUT" | cut -f1)"
