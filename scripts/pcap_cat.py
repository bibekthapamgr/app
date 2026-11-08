#!/usr/bin/env python3
"""Appends the records of one pcap file to another, in place (M12-81): when the emulator process exits at scenario R's
restart and is started again from its disk, the new process writes its `-tcpdump` capture to a second file, and the
evidence scripts read one. The first file is cut back to its last whole record first (a process that died may have
left half a record, which would swallow everything appended after it); the second's records follow, header left out.
Both must be pcap (not pcapng) of the same byte order and link type; otherwise nothing is changed and the reason is the
exit status 1 line.

    python3 scripts/pcap_cat.py <first, grown in place> <second>

Exit 0 when appended, when the second file is missing or empty (nothing to add), or when the first is missing (the second
is copied to its place); 1 when the files do not fit.
"""

from __future__ import annotations

import os
import shutil
import struct
import sys

MAGICS = {
    b"\xd4\xc3\xb2\xa1": "<",
    b"\x4d\x3c\xb2\xa1": "<",
    b"\xa1\xb2\xc3\xd4": ">",
    b"\xa1\xb2\x3c\x4d": ">",
}
HEADER = 24
RECORD = 16


def whole_prefix(data: bytes, endian: str) -> int:
    """How many bytes of data hold the header and whole records only."""
    at = HEADER
    while at + RECORD <= len(data):
        incl_len = struct.unpack(endian + "I", data[at + 8 : at + 12])[0]
        if at + RECORD + incl_len > len(data):
            break
        at += RECORD + incl_len
    return at


def describe(path: str, data: bytes) -> tuple[str, int] | str:
    """(byte order, link type) of a pcap file, or why it is not one."""
    if len(data) < HEADER:
        return f"{path}: {len(data)} bytes, no pcap header"
    endian = MAGICS.get(data[:4])
    if endian is None:
        return f"{path}: not a pcap file (magic {data[:4].hex()}; pcapng is not read)"
    return endian, struct.unpack(endian + "I", data[20:24])[0] & 0x0FFFFFFF


def cat(first: str, second: str) -> tuple[bool, str]:
    if not os.path.exists(second) or os.path.getsize(second) == 0:
        return True, f"{second}: nothing to append"
    if not os.path.exists(first):
        shutil.copyfile(second, first)
        return True, f"{first} was missing: {second} stands in for it"
    with open(first, "rb") as f:
        a = f.read()
    with open(second, "rb") as f:
        b = f.read()
    shape_a, shape_b = describe(first, a), describe(second, b)
    if isinstance(shape_a, str):
        return False, shape_a
    if isinstance(shape_b, str):
        return False, shape_b
    if shape_a != shape_b:
        return False, f"the files differ: {first} is {shape_a}, {second} is {shape_b}"
    endian = shape_a[0]
    keep, add = whole_prefix(a, endian), whole_prefix(b, endian)
    with open(first, "wb") as f:
        f.write(a[:keep])
        f.write(b[HEADER:add])
    cut = f", {len(a) - keep} bytes of a cut-off last record dropped" if keep != len(a) else ""
    return True, f"{first}: {add - HEADER} bytes of records appended from {second}{cut}"


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    ok, why = cat(argv[0], argv[1])
    print(why)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
