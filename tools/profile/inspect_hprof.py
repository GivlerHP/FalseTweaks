"""Stream HPROF primitive arrays without loading the heap graph into RAM."""
import collections
import heapq
import json
import struct
import sys


TYPE_SIZE = {4: 1, 5: 2, 6: 4, 7: 8, 8: 1, 9: 2, 10: 4, 11: 8}
ROOT_SIZE = {0x01: 2, 0x02: 2, 0x03: 2, 0x04: 2, 0x05: 1,
             0x06: 2, 0x07: 1, 0x08: 3, 0x89: 1, 0x8a: 1,
             0x8b: 1, 0x8c: 1, 0x8d: 1, 0x8e: 3, 0x90: 1}


def scan(path):
    counts = collections.Counter()
    bytes_by_size = collections.Counter()
    top = []
    with open(path, "rb", buffering=4 * 1024 * 1024) as f:
        header = bytearray()
        while True:
            c = f.read(1)
            if c == b"\0":
                break
            if not c:
                raise ValueError("Incomplete HPROF header")
            header += c
        if not header.startswith(b"JAVA PROFILE"):
            raise ValueError("Not a Java HPROF file")
        ident_size = struct.unpack(">I", f.read(4))[0]
        f.read(8)

        def read_u1():
            return f.read(1)[0]

        def read_u2():
            return struct.unpack(">H", f.read(2))[0]

        def read_u4():
            return struct.unpack(">I", f.read(4))[0]

        def skip_value(t):
            f.seek(TYPE_SIZE.get(t, ident_size), 1)

        while True:
            tag = f.read(1)
            if not tag:
                break
            f.read(4)
            length = read_u4()
            end = f.tell() + length
            if tag[0] not in (0x0c, 0x1c):
                f.seek(end)
                continue
            while f.tell() < end:
                sub = read_u1()
                if sub in ROOT_SIZE:
                    count = ROOT_SIZE[sub]
                    # Thread/object root extras use fixed 4-byte integers.
                    if sub in (0x02, 0x03):
                        f.seek(ident_size + 8, 1)
                    elif sub == 0x04 or sub == 0x06:
                        f.seek(ident_size + 4, 1)
                    elif sub in (0x08, 0x8e):
                        f.seek(ident_size + 8, 1)
                    else:
                        f.seek(ident_size * count, 1)
                elif sub == 0xff:
                    f.seek(ident_size, 1)
                elif sub == 0xfe:
                    f.seek(4 + ident_size, 1)
                elif sub == 0x20:
                    f.seek(ident_size + 4 + ident_size * 6 + 4, 1)
                    for _ in range(read_u2()):
                        f.seek(2, 1)
                        skip_value(read_u1())
                    for _ in range(read_u2()):
                        f.seek(ident_size, 1)
                        skip_value(read_u1())
                    for _ in range(read_u2()):
                        f.seek(ident_size + 1, 1)
                elif sub == 0x21:
                    f.seek(ident_size + 4 + ident_size, 1)
                    f.seek(read_u4(), 1)
                elif sub == 0x22:
                    f.seek(ident_size + 4, 1)
                    n = read_u4()
                    f.seek(ident_size + n * ident_size, 1)
                elif sub in (0x23, 0xc3):
                    obj_id = int.from_bytes(f.read(ident_size), "big")
                    f.seek(4, 1)
                    n = read_u4()
                    t = read_u1()
                    size = n * TYPE_SIZE[t]
                    if t == 8:
                        counts[n] += 1
                        bytes_by_size[n] += size
                        item = (size, obj_id)
                        if len(top) < 30:
                            heapq.heappush(top, item)
                        elif item > top[0]:
                            heapq.heapreplace(top, item)
                    if sub == 0x23:
                        f.seek(size, 1)
                else:
                    raise ValueError("Unexpected heap subrecord 0x%02x at %d" % (sub, f.tell() - 1))
            if f.tell() != end:
                raise ValueError("Misaligned record at %d (expected %d)" % (f.tell(), end))
    return {"array_count": sum(counts.values()), "array_bytes": sum(bytes_by_size.values()),
            "largest": [{"bytes": n, "object_id": hex(obj)} for n, obj in sorted(top, reverse=True)],
            "sizes_by_total_bytes": [{"length": n, "count": counts[n], "bytes": total}
                                     for n, total in bytes_by_size.most_common(40)]}


if __name__ == "__main__":
    print(json.dumps(scan(sys.argv[1]), indent=2))
