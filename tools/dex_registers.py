#!/usr/bin/env python3
"""Register counts of every method in a release build's DEX code.

Media 4.5 crashed at launch with

    java.lang.VerifyError: ... HomeScaffold(...) failed to verify:
    copy-cat1 v0<-v258 type=Reference: com.media.app.AppMediaItem

HomeScaffold had grown into one method needing more than 256 Dalvik
registers (the verifier names v258). Above 256, most instructions cannot
reach their operands directly and the compiler has to shuttle values through
the low registers; here it moved an object reference with a plain `move`,
ART's verifier rejected the class, and the app could not start.

This reads the DEX files in a build and lists the methods with the most
registers. It exits 1 if any method of the app's package is over the limit
(255 by default), so a build that could hit that failure is caught first.

    python3 tools/dex_registers.py app/build/intermediates/dex/release --package com.media.app
    python3 tools/dex_registers.py app/build/outputs/apk/debug/app-arm64-v8a-debug.apk --package com.media.app

Takes .dex files, folders (searched recursively), and .apk / .aab / .zip
files (every .dex inside). No dependencies beyond Python 3.
"""

import argparse
import os
import struct
import sys
import zipfile


def uleb128(data, pos):
    result = 0
    shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7


def mutf8(data, off):
    """A string_data_item: uleb128 length, then modified UTF-8 up to a 0."""
    _, pos = uleb128(data, off)
    end = data.index(b"\x00", pos)
    raw = data[pos:end]
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("utf-8", "replace")


def pretty_type(desc):
    dims = 0
    while desc.startswith("["):
        dims += 1
        desc = desc[1:]
    prims = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char",
             "I": "int", "J": "long", "F": "float", "D": "double"}
    if desc in prims:
        name = prims[desc]
    elif desc.startswith("L") and desc.endswith(";"):
        name = desc[1:-1].replace("/", ".")
    else:
        name = desc
    return name + "[]" * dims


def methods_of(data, source):
    """Yields (registers, ins, insns_units, class, name, params, source)."""
    if data[:4] != b"dex\n":
        raise ValueError(f"{source}: not a DEX file")
    (string_ids_size, string_ids_off, type_ids_size, type_ids_off,
     proto_ids_size, proto_ids_off, _field_ids_size, _field_ids_off,
     method_ids_size, method_ids_off, class_defs_size, class_defs_off) = \
        struct.unpack_from("<12I", data, 56)

    strings = {}

    def string(i):
        s = strings.get(i)
        if s is None:
            (off,) = struct.unpack_from("<I", data, string_ids_off + 4 * i)
            s = strings[i] = mutf8(data, off)
        return s

    def type_name(i):
        (desc_idx,) = struct.unpack_from("<I", data, type_ids_off + 4 * i)
        return pretty_type(string(desc_idx))

    def params(proto_idx):
        _shorty, _ret, params_off = struct.unpack_from("<3I", data, proto_ids_off + 12 * proto_idx)
        if params_off == 0:
            return ""
        (n,) = struct.unpack_from("<I", data, params_off)
        idx = struct.unpack_from(f"<{n}H", data, params_off + 4)
        return ", ".join(type_name(t) for t in idx)

    for c in range(class_defs_size):
        class_idx, _acc, _sup, _ifc, _src, _ann, class_data_off, _sv = \
            struct.unpack_from("<8I", data, class_defs_off + 32 * c)
        if class_data_off == 0:
            continue
        pos = class_data_off
        sf, pos = uleb128(data, pos)
        inf, pos = uleb128(data, pos)
        dm, pos = uleb128(data, pos)
        vm, pos = uleb128(data, pos)
        for _ in range(sf + inf):
            _, pos = uleb128(data, pos)
            _, pos = uleb128(data, pos)
        for count in (dm, vm):
            midx = 0
            for _ in range(count):
                diff, pos = uleb128(data, pos)
                _, pos = uleb128(data, pos)       # access flags
                code_off, pos = uleb128(data, pos)
                midx += diff
                if code_off == 0:                 # abstract / native
                    continue
                registers, ins, _outs, _tries, _dbg, insns = \
                    struct.unpack_from("<4H2I", data, code_off)
                m_class, m_proto, m_name = struct.unpack_from("<HHI", data, method_ids_off + 8 * midx)
                yield (registers, ins, insns, type_name(m_class), string(m_name),
                       params(m_proto), source)


def dex_blobs(paths):
    for path in paths:
        if os.path.isdir(path):
            for root, _dirs, files in os.walk(path):
                for f in sorted(files):
                    if f.endswith(".dex"):
                        p = os.path.join(root, f)
                        with open(p, "rb") as fh:
                            yield p, fh.read()
        elif zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as z:
                for name in z.namelist():
                    if name.endswith(".dex"):
                        yield f"{path}!{name}", z.read(name)
        else:
            with open(path, "rb") as fh:
                yield path, fh.read()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("paths", nargs="+", help=".dex / .apk / .aab / .zip files or folders")
    ap.add_argument("--limit", type=int, default=255,
                    help="fail if any method needs more registers than this (default 255)")
    ap.add_argument("--top", type=int, default=15, help="how many of the largest methods to list")
    ap.add_argument("--package", default="",
                    help="the app's own package, e.g. com.media.app: its largest methods are "
                         "listed, and only its methods count against --limit. Library code "
                         "is reported but cannot be split from here")
    args = ap.parse_args()

    found = []
    files = 0
    for source, data in dex_blobs(args.paths):
        files += 1
        found.extend(methods_of(data, source))
    if files == 0:
        print("No .dex files found in: " + ", ".join(args.paths), file=sys.stderr)
        return 2

    found.sort(key=lambda m: m[0], reverse=True)

    def show(rows):
        for registers, ins, insns, cls, name, prm, source in rows:
            print(f"  {registers:6d}  {cls}.{name}({prm})")
            print(f"          {insns} code units, {ins} in; {os.path.basename(source)}")

    print(f"{len(found)} methods with code in {files} DEX file(s).")
    print(f"\nLargest {min(args.top, len(found))} by registers:")
    show(found[:args.top])
    if args.package:
        own = [m for m in found if m[3].startswith(args.package + ".")]
        print(f"\nLargest {min(args.top, len(own))} in {args.package}:")
        show(own[:args.top])

    over = [m for m in found if m[0] > args.limit]
    if args.package:
        ours = [m for m in over if m[3].startswith(args.package + ".")]
        libs = [m for m in over if m not in ours]
        if libs:
            print(f"\nNote: {len(libs)} library method(s) over {args.limit} registers. "
                  f"They ship in every app that uses that library; not ours to split.")
            show(libs)
        over = ours
        scope = f"{args.package} method"
        largest = max((m[0] for m in found if m[3].startswith(args.package + ".")), default=0)
    else:
        scope = "method"
        largest = found[0][0] if found else 0
    if over:
        print(f"\nFAIL: {len(over)} {scope}(s) over {args.limit} registers. A method this "
              f"large is what 4.5's launch VerifyError came from. Split it.")
        show(over)
        return 1
    print(f"\nOK: no {scope} over {args.limit} registers (largest: {largest}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
