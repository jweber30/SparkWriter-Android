#!/usr/bin/env python3
"""Save a PNG of the VM screen via the QEMU monitor socket (standard library only).
Usage: screenshot.py <work-dir> <out.png>"""
import socket, sys, time, zlib, struct, os

work, out = sys.argv[1], sys.argv[2]
ppm = os.path.abspath(out) + ".ppm"
s = socket.socket(socket.AF_UNIX); s.connect(os.path.join(work, "monitor.sock")); s.settimeout(5)
try: s.recv(4096)
except socket.timeout: pass
s.sendall(f"screendump {ppm}\n".encode()); time.sleep(1.5); s.close()
data = open(ppm, "rb").read(); os.remove(ppm)
parts = data.split(b"\n", 3)                      # P6 / w h / maxval / pixels
w, h = map(int, parts[1].split()); px = parts[3]
raw = b"".join(b"\x00" + px[y*w*3:(y+1)*w*3] for y in range(h))
def chunk(t, d): c = struct.pack(">I", len(d)) + t + d; return c + struct.pack(">I", zlib.crc32(t + d))
open(out, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
