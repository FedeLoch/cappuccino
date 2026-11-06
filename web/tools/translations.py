#!/usr/bin/env python3
"""Copies the Spanish table from Translations.kt into a JavaScript module, so the web version
and the Android app share one list of translations.

Usage: translations.py Translations.kt out.js
"""
import json
import re
import sys

source, out = sys.argv[1], sys.argv[2]
text = open(source, encoding="utf-8").read()
start = text.index("val SPANISH = mapOf(")
body = text[start:]
# Each entry is "English" to "Spanish", with Kotlin string escapes.
pair = re.compile(r'"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"')


def unescape(value):
    return json.loads('"' + value.replace("\\$", "$") + '"')


table = {unescape(english): unescape(spanish) for english, spanish in pair.findall(body)}
with open(out, "w", encoding="utf-8") as file:
    file.write("// Generated from Translations.kt by web/tools/translations.py. Don't edit by hand.\n")
    file.write("export const SPANISH = ")
    json.dump(table, file, ensure_ascii=False, indent=0)
    file.write(";\n")
print(f"{len(table)} translations")
