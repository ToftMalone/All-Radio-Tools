#!/usr/bin/env python3
"""Builds app/src/main/assets/oui.tsv.gz from the IEEE MA-L registry (public listing): one "XXXXXX<TAB>Name" line
per 24-bit prefix, sorted, for the vendor shown next to Wi-Fi and Bluetooth addresses."""
import csv, gzip, io, os, sys, urllib.request

URL = "https://standards-oui.ieee.org/oui/oui.csv"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "oui.tsv.gz")

def main():
    src = sys.argv[1] if len(sys.argv) > 1 else None
    data = open(src, "rb").read() if src else urllib.request.urlopen(URL, timeout=60).read()
    rows = {}
    for r in csv.DictReader(io.StringIO(data.decode("utf-8", "replace"))):
        if r["Registry"] != "MA-L":
            continue
        name = " ".join(r["Organization Name"].split())
        if name:
            rows[r["Assignment"].upper()] = name
    text = "".join(f"{k}\t{rows[k]}\n" for k in sorted(rows))
    with gzip.GzipFile(OUT, "wb", 9, mtime=0) as f:
        f.write(text.encode("utf-8"))
    print(len(rows), "prefixes,", os.path.getsize(OUT), "bytes")

if __name__ == "__main__":
    main()
