#!/usr/bin/env python3
"""Checks every translation against the English strings before a build sees them.

    python3 tools/check_translations.py

For each res/values-xx/strings.xml:
  * every English string and plural is present, and nothing extra;
  * each string uses exactly the same format placeholders as English
    (%1$s, %2$d, %1$+.1f, %%...). A mismatch is not a typo: getString()
    throws IllegalFormatConversionException at runtime, or silently drops
    the value;
  * plurals carry the forms that language's rules use (Russian needs one,
    few, many and other) and every form keeps the placeholders;
  * apostrophes and double quotes are escaped, which aapt2 otherwise fails
    on, or worse, accepts and strips;
  * no translation is empty unless English is.
Exits 1 on any problem, listing each one.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

# CLDR plural categories each shipped language needs at minimum. Android
# falls back to "other" for a missing form, so "other" is always required;
# the rest are the forms the language's rules actually select.
REQUIRED_PLURALS = {
    "ru": {"one", "few", "many", "other"},
    "es": {"one", "other"}, "pt": {"one", "other"}, "fr": {"one", "other"},
    "de": {"one", "other"}, "tr": {"one", "other"}, "hi": {"one", "other"},
    "ja": {"other"}, "vi": {"other"}, "in": {"other"},
}

SPEC = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]")


def specs(text):
    return sorted(SPEC.findall(text or ""))


def raw_texts(path):
    """name -> raw inner text, to check escaping the parser would hide."""
    src = open(path, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', src, re.S):
        out[m.group(1)] = m.group(2)
    for m in re.finditer(r'<plurals name="([^"]+)"[^>]*>(.*?)</plurals>', src, re.S):
        for q in re.finditer(r'<item quantity="([a-z]+)">(.*?)</item>', m.group(2), re.S):
            out[f"{m.group(1)}#{q.group(1)}"] = q.group(2)
    return out


def load(path):
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for el in root:
        name = el.get("name")
        if el.tag == "string":
            if el.get("translatable") == "false":
                continue
            strings[name] = "".join(el.itertext())
        elif el.tag == "plurals":
            plurals[name] = {i.get("quantity"): "".join(i.itertext()) for i in el.findall("item")}
    return strings, plurals


def unescaped(raw):
    """An apostrophe or double quote not preceded by a backslash."""
    return re.search(r"(?<!\\)['\"]", raw) is not None


def main():
    base_path = os.path.join(RES, "values", "strings.xml")
    en_s, en_p = load(base_path)
    problems = []
    for name, raw in raw_texts(base_path).items():
        if unescaped(raw):
            problems.append(f"values: {name}: unescaped ' or \"")

    locales = sorted(d for d in os.listdir(RES)
                     if d.startswith("values-") and os.path.exists(os.path.join(RES, d, "strings.xml")))
    if not locales:
        problems.append("no translations found")
    for d in locales:
        lang = d.split("-")[1]
        path = os.path.join(RES, d, "strings.xml")
        try:
            s, p = load(path)
        except ET.ParseError as e:
            problems.append(f"{d}: not valid XML: {e}")
            continue
        for name, raw in raw_texts(path).items():
            if unescaped(raw):
                problems.append(f"{d}: {name}: unescaped ' or \"")
        for k in sorted(set(en_s) - set(s)):
            problems.append(f"{d}: missing string {k}")
        for k in sorted(set(s) - set(en_s)):
            problems.append(f"{d}: string {k} is not in English")
        for k in sorted(set(en_s) & set(s)):
            if specs(s[k]) != specs(en_s[k]):
                problems.append(f"{d}: {k}: placeholders {specs(s[k])} != English {specs(en_s[k])}")
            if en_s[k].strip() and not s[k].strip():
                problems.append(f"{d}: {k}: empty")
        for k in sorted(set(en_p) - set(p)):
            problems.append(f"{d}: missing plural {k}")
        for k in sorted(set(p) - set(en_p)):
            problems.append(f"{d}: plural {k} is not in English")
        need = REQUIRED_PLURALS.get(lang, {"other"})
        for k in sorted(set(en_p) & set(p)):
            forms = p[k]
            for q in sorted(need - set(forms)):
                problems.append(f"{d}: plural {k}: missing '{q}'")
            want = specs(en_p[k]["other"])
            for q, text in forms.items():
                if specs(text) != want:
                    problems.append(f"{d}: plural {k}[{q}]: placeholders {specs(text)} != English {want}")
                if not text.strip():
                    problems.append(f"{d}: plural {k}[{q}]: empty")

    if problems:
        print(f"{len(problems)} problem(s):")
        for x in problems:
            print("  " + x)
        return 1
    print(f"OK: {len(en_s)} strings and {len(en_p)} plurals, complete and consistent in "
          f"{len(locales)} languages: {', '.join(l.split('-', 1)[1] for l in locales)}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
