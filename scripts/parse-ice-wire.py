"""
按索引逐字节打印 MoveTask 请求，并精确定位参数区。

Ice 3.7 请求体布局（请求头里的 magic/encoding 之后）：
    requestId(4)  facetSize(4)+facet  opSize(4)+op  mode(1)  ctxCount(1)
    [ keyLen(1)+key  valLen(1)+val ]*        ← Context
    encapSize(4, 小端)  encMajor(1)  encMinor(1)
    ★ 参数区
"""

import sys

OP_NAMES = {0: "Left", 1: "Right", 2: "Up", 3: "Down"}


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "target/wire-c2s.bin"
    s = open(path, "rb").read()
    print(f"raw bytes: {path}, total {len(s)} bytes\n")

    idx = 0
    n = 0
    while True:
        i = s.find(b"MoveTask", idx)
        if i < 0:
            break
        n += 1
        op_end = i + len(b"MoveTask")

        p = op_end
        mode = s[p]; p += 1
        ctx_count = s[p]; p += 1

        ctx = {}
        for _ in range(ctx_count):
            klen = s[p]; p += 1
            key = s[p:p + klen].decode("utf-8"); p += klen
            vlen = s[p]; p += 1
            val = s[p:p + vlen].decode("utf-8"); p += vlen
            ctx[key] = val

        enc_size = int.from_bytes(s[p:p + 4], "little"); p += 4
        enc_major, enc_minor = s[p], s[p + 1]; p += 2
        params = s[p:]

        print("=" * 74)
        print(f"MoveTask request #{n}")
        print("=" * 74)
        print(f"  mode              = {mode} (0=Normal)")
        print(f"  ctxCount          = {ctx_count}")
        print(f"  CONTEXT           = "
              + (", ".join(f"{k!r}={v!r}" for k, v in ctx.items())
                 if ctx else "(empty - no strArmID in request)"))
        print(f"  encapSize         = {enc_size}")
        print(f"  encoding          = {enc_major}.{enc_minor}")

        print(f"\n  *** params start offset = {p}, length = {len(params)} bytes")
        print(f"     bytes : {' '.join(f'{x:02X}' for x in params)}")
        print(f"     offset: " + "  ".join(f"[{p + k}]={x:02X}" for k, x in enumerate(params)))

        if len(params) >= 5:
            ed = params[0]
            speed = int.from_bytes(params[1:5], "little")
            print(f"\n     ed     (offset {p}+0) = 0x{ed:02X} ({ed})  ->  EDirection.{OP_NAMES.get(ed, '?')}")
            print(f"     nSpeed (offset {p}+1..{p + 4}) = {speed}  (0x{speed:08X}, little-endian int32)")
            print(f"\n  >> C# receives: MoveTask(EDirection.{OP_NAMES.get(ed, '?')}, {speed})")
            print(f"  >> C# current.ctx[\"strArmID\"] = "
                  + (repr(ctx.get("strArmID")) if ctx else "null"))

        idx = op_end
        print()

    print(f"total {n} MoveTask requests")


if __name__ == "__main__":
    main()
