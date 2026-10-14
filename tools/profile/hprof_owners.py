"""Count immediate holders of 2/4 KiB byte arrays in a Java 8 HPROF dump."""
import collections
import json
import struct
import sys

from inspect_hprof import ROOT_SIZE, TYPE_SIZE


WANTED = {
    "net/minecraft/world/chunk/NibbleArray",
    "net/minecraft/world/chunk/storage/ExtendedBlockStorage",
    "net/minecraft/nbt/NBTTagByteArray",
}


def read_dump(path, first, targets=None, classes=None):
    strings = {} if first else None
    names = {} if first else None
    owned = collections.Counter()
    holders = collections.Counter()
    with open(path, "rb", buffering=4 * 1024 * 1024) as f:
        while f.read(1) != b"\0":
            pass
        ident_size = struct.unpack(">I", f.read(4))[0]
        f.read(8)

        def u1():
            return f.read(1)[0]

        def u2():
            return struct.unpack(">H", f.read(2))[0]

        def u4():
            return struct.unpack(">I", f.read(4))[0]

        while True:
            tag = f.read(1)
            if not tag:
                break
            f.read(4)
            length = u4()
            end = f.tell() + length
            if first and tag[0] == 0x01:
                key = int.from_bytes(f.read(ident_size), "big")
                strings[key] = f.read(length - ident_size).decode("utf-8", "replace")
            elif first and tag[0] == 0x02:
                f.read(4)
                class_id = int.from_bytes(f.read(ident_size), "big")
                f.read(4)
                names[class_id] = int.from_bytes(f.read(ident_size), "big")
            elif tag[0] in (0x0c, 0x1c):
                while f.tell() < end:
                    sub = u1()
                    if sub in ROOT_SIZE:
                        if sub in (0x02, 0x03, 0x08, 0x8e):
                            f.seek(ident_size + 8, 1)
                        elif sub in (0x04, 0x06):
                            f.seek(ident_size + 4, 1)
                        else:
                            f.seek(ident_size * ROOT_SIZE[sub], 1)
                    elif sub == 0xff:
                        f.seek(ident_size, 1)
                    elif sub == 0xfe:
                        f.seek(4 + ident_size, 1)
                    elif sub == 0x20:
                        f.seek(ident_size + 4 + ident_size * 6 + 4, 1)
                        for _ in range(u2()):
                            f.seek(2, 1)
                            t = u1()
                            f.seek(TYPE_SIZE.get(t, ident_size), 1)
                        for _ in range(u2()):
                            f.seek(ident_size, 1)
                            t = u1()
                            f.seek(TYPE_SIZE.get(t, ident_size), 1)
                        f.seek(u2() * (ident_size + 1), 1)
                    elif sub == 0x21:
                        f.seek(ident_size + 4, 1)
                        class_id = int.from_bytes(f.read(ident_size), "big")
                        size = u4()
                        if not first and class_id in classes:
                            holders[classes[class_id]] += 1
                            data = f.read(size)
                            seen = set()
                            for offset in range(size - ident_size + 1):
                                obj = int.from_bytes(data[offset:offset + ident_size], "big")
                                if obj in targets and obj not in seen:
                                    owned[(classes[class_id], targets[obj])] += 1
                                    seen.add(obj)
                        else:
                            f.seek(size, 1)
                    elif sub == 0x22:
                        f.seek(ident_size + 4, 1)
                        n = u4()
                        f.seek(ident_size + n * ident_size, 1)
                    elif sub in (0x23, 0xc3):
                        obj = int.from_bytes(f.read(ident_size), "big")
                        f.seek(4, 1)
                        n = u4()
                        t = u1()
                        if first and t == 8 and n in (2048, 4096):
                            targets[obj] = n
                        if sub == 0x23:
                            f.seek(n * TYPE_SIZE[t], 1)
                    else:
                        raise ValueError("Unknown heap subrecord %x at %d" % (sub, f.tell()))
            f.seek(end)
    if first:
        classes = {class_id: strings[string_id] for class_id, string_id in names.items()
                   if strings.get(string_id) in WANTED}
        return classes
    return holders, owned


if __name__ == "__main__":
    path = sys.argv[1]
    target_arrays = {}
    class_ids = read_dump(path, True, target_arrays)
    holders, owned = read_dump(path, False, target_arrays, class_ids)
    print(json.dumps({"target_arrays": len(target_arrays), "holders": holders,
                      "direct_array_refs": {"%s -> byte[%d]" % key: value
                                            for key, value in owned.items()}}, indent=2))
