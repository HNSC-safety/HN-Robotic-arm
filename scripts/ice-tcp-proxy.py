"""
Ice 线上原始字节抓包器 —— 以【透明 TCP 代理】的身份夹在中间。

    Java Ice 客户端  ──►  本代理  ──►  真 Ice 服务端

因为只是转发、不解析协议，所以握手一定成功；
转发的每一包原样写入二进制文件，看到的就是真正跑在网络上的字节。

输出：
    target/wire-c2s.bin   客户端 → 服务端
    target/wire-s2c.bin   服务端 → 客户端
    target/wire-dump.txt  人看的 hex dump
"""

import socket
import sys
import threading

LISTEN = int(sys.argv[1]) if len(sys.argv) > 1 else 10099
TARGET = int(sys.argv[2]) if len(sys.argv) > 2 else 10036
TARGET_HOST = "127.0.0.1"
OUT_C2S = "target/wire-c2s.bin"
OUT_S2C = "target/wire-s2c.bin"
OUT_TXT = "target/wire-dump.txt"

lock = threading.Lock()
txt = open(OUT_TXT, "w", encoding="utf-8")
c2s = open(OUT_C2S, "wb")
s2c = open(OUT_S2C, "wb")


def dump(tag, data):
    lines = [f"\n<<< {tag}  {len(data)} 字节 >>>"]
    for i in range(0, len(data), 16):
        chunk = data[i:i + 16]
        hexs = " ".join(f"{b:02X}" for b in chunk)
        ascii_ = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        lines.append(f"  {i:04X}  {hexs:<47}  |{ascii_}|")
    with lock:
        txt.write("\n".join(lines) + "\n")
        txt.flush()


def pump(src, dst, tag, sink):
    try:
        while True:
            data = src.recv(8192)
            if not data:
                break
            dump(tag, data)
            with lock:
                sink.write(data)
                sink.flush()
            dst.sendall(data)
    except Exception as e:
        with lock:
            txt.write(f"[{tag}] 结束：{type(e).__name__}: {e}\n")
            txt.flush()
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except Exception:
            pass


def handle(client, addr):
    with lock:
        txt.write(f"\n===== 新连接 {addr} -> {TARGET_HOST}:{TARGET} =====\n")
        txt.flush()
    try:
        server = socket.create_connection((TARGET_HOST, TARGET), timeout=10)
    except Exception as e:
        with lock:
            txt.write(f"[代理] 连不上目标：{e}\n")
            txt.flush()
        client.close()
        return

    t1 = threading.Thread(target=pump, args=(client, server, "C2S", c2s), daemon=True)
    t2 = threading.Thread(target=pump, args=(server, client, "S2C", s2c), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    client.close()
    server.close()


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", LISTEN))
    srv.listen(5)
    with lock:
        txt.write(f"[代理] 127.0.0.1:{LISTEN} -> {TARGET_HOST}:{TARGET}\n")
        txt.flush()
    while True:
        conn, addr = srv.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()


if __name__ == "__main__":
    main()
