#!/usr/bin/env python3
"""Build CC-BY 3.0 Vietnamese-English offline Anki lookups from VNEDICT.

Source: Paul Denisowski, VNEDICT, CC BY 3.0.
Packaged StarDict conversion from cer28/stardict-vnedict.
No Google APIs, model downloads or network requests at application runtime.
"""
import gzip
import html
import os
import re
import struct
import sys
import urllib.request
from pathlib import Path

ORIGIN = "https://raw.githubusercontent.com/cer28/stardict-vnedict/96f54e16f1ed1062a4b6c817711f3b097e477e2d/compiled/"
HEADERS = {"User-Agent": "AndorsTrail-Offline-Word-Dictionary/1.0"}
REQUIRED = {"độc lập", "bạn", "người", "muốn", "một", "là"}


def read_public_data(filename):
    url = ORIGIN + filename
    print("Downloading openly licensed VNEDICT asset:", url, flush=True)
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def clean_definition(data):
    string = data.decode("utf-8", errors="replace")
    string = html.unescape(re.sub(r"<[^>]+>", " ", string))
    string = string.replace("\x00", " ")
    parts = [re.sub(r"\s+", " ", line).strip(" \t-/;,") for line in string.splitlines()]
    # StarDict entries can contain format markers, usage examples and several
    # senses. Keep short readable English glosses for unobtrusive game UI.
    parts = [line for line in parts if line and not line.startswith(("*", "#", "@"))]
    if not parts:
        return ""
    return " ; ".join(parts[:3])[:280]


def main(outdir):
    folder = Path(outdir)
    folder.mkdir(parents=True, exist_ok=True)
    idx = read_public_data("vnedict.idx")
    compressed = read_public_data("vnedict.dict.dz")
    contents = gzip.decompress(compressed)
    print("Dictionary index bytes:", len(idx), "decompressed bytes:", len(contents), flush=True)

    dictionary = {}
    position = 0
    while position < len(idx):
        end = idx.find(b"\x00", position)
        if end < 0 or end + 9 > len(idx):
            raise ValueError("Invalid StarDict index at byte %d" % position)
        word = idx[position:end].decode("utf-8").strip().lower()
        offset, length = struct.unpack(">II", idx[end + 1:end + 9])
        if offset + length > len(contents):
            raise ValueError("StarDict definition offset outside compressed stream")
        value = clean_definition(contents[offset:offset + length])
        if word and value and "\t" not in word and "\n" not in word:
            dictionary.setdefault(word, value)
        position = end + 9

    # Common closed-class words: these must always be useful in a vocabulary
    # game, even when a dictionary's headword conventions vary.
    common = {
        "độc lập": "independent; autonomous; self-reliant",
        "muốn": "want; wish; desire",
        "bạn": "you; friend",
        "người": "person; human; people",
        "một": "one; a; an",
        "là": "to be; is; am; are",
        "tôi": "I; me",
        "của": "of; belonging to",
        "không": "not; no",
        "có": "have; yes; there is",
        "và": "and",
        "ở": "at; in; live",
        "những": "plural marker; some",
        "các": "plural marker",
        "được": "can; be able to; to receive",
        "với": "with",
        "nhưng": "but",
        "rất": "very",
        "này": "this",
        "đó": "that",
    }
    dictionary.update(common)
    if not REQUIRED.issubset(dictionary):
        raise ValueError("Missing core Vietnamese headwords: " + str(REQUIRED - dictionary.keys()))
    if len(dictionary) < 30000:
        raise ValueError("Dictionary appears too small: %d headwords" % len(dictionary))

    vi_file = folder / "anki_vi_en_dictionary.tsv"
    vi_file.write_text(
        "# VNEDICT by Paul Denisowski, CC BY 3.0; https://www.denisowski.org/Vietnamese/vnedict_readme.htm\n"
        + "".join(k + "\t" + v + "\n" for k, v in sorted(dictionary.items())),
        encoding="utf-8",
    )

    # Minimal English -> Vietnamese support from unambiguous glosses and
    # essential words. Don't hallucinate full-sentence machine translation.
    reverse = {}
    for vietnamese, meaning in dictionary.items():
        for term in re.split(r"[;,/]", meaning.lower()):
            term = term.strip(" \t.()")
            if re.fullmatch(r"[a-z][a-z '-]{0,35}", term) and len(term.split()) <= 3:
                if len(reverse) < 150000:
                    reverse.setdefault(term, vietnamese)
    for en, vi in {
        "you": "bạn",
        "independent": "độc lập",
        "autonomous": "độc lập",
        "want": "muốn",
        "friend": "bạn",
        "person": "người",
        "people": "người",
        "one": "một",
        "i": "tôi",
        "me": "tôi",
        "and": "và",
        "but": "nhưng",
        "with": "với",
        "no": "không",
        "yes": "có",
    }.items():
        reverse[en] = vi
    en_file = folder / "anki_en_vi_dictionary.tsv"
    en_file.write_text(
        "# Reverse English-to-Vietnamese lookup generated from VNEDICT (CC BY 3.0)\n"
        + "".join(k + "\t" + v + "\n" for k, v in sorted(reverse.items())),
        encoding="utf-8",
    )
    print("Offline Vietnamese-English entries:", len(dictionary),
          "English-Vietnamese entries:", len(reverse), flush=True)
    print("độc lập =>", dictionary["độc lập"], flush=True)
    print("bundled sizes:", vi_file.stat().st_size, en_file.stat().st_size, flush=True)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: build_offline_anki_dictionary.py <Android assets folder>")
    main(sys.argv[1])
