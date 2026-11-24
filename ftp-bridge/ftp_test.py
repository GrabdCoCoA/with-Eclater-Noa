#!/usr/bin/env python3
"""FTP-over-Tailscale-tunnel test client.

Opens TCP connections to a tailnet address through the runtime HTTP
proxy (port 3130 = Tailscale tunnel) using CONNECT, then speaks FTP.
Usage: ftp_test.py <host> <port> [user] [password]
Stage 1: banner + login + PWD + EPSV/PASV data connection + LIST /
"""
import os
import re
import socket
import sys

def proxy_addr():
    p = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if not p:
        raise SystemExit("no HTTPS_PROXY in env")
    # strip scheme and credentials, keep host, swap port to 3130
    rest = p.split("://", 1)[-1]
    rest = rest.split("@")[-1]
    host = rest.split(":")[0].split("/")[0]
    return host, 3130

def connect_via_proxy(host, port, timeout=60):
    ph, pp = proxy_addr()
    s = socket.create_connection((ph, pp), timeout=timeout)
    req = f"CONNECT {host}:{port} HTTP/1.1\r\nHost: {host}:{port}\r\n\r\n"
    s.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = s.recv(4096)
        if not chunk:
            raise SystemExit("proxy closed connection during CONNECT")
        buf += chunk
    status_line = buf.split(b"\r\n", 1)[0].decode("utf-8", "replace")
    print(f"[proxy] CONNECT {host}:{port} -> {status_line}")
    if " 200 " not in status_line and not status_line.endswith(" 200"):
        raise SystemExit(f"CONNECT failed: {status_line}")
    # any bytes after header belong to the tunneled stream
    rest = buf.split(b"\r\n\r\n", 1)[1]
    return s, rest

class FTP:
    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.sock, self.buf = connect_via_proxy(host, port)
        self.buf = self.buf or b""

    def _readline(self):
        while b"\r\n" not in self.buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise SystemExit("server closed connection")
            self.buf += chunk
        line, self.buf = self.buf.split(b"\r\n", 1)
        return line.decode("utf-8", "replace")

    def read_reply(self):
        lines = []
        while True:
            line = self._readline()
            lines.append(line)
            if len(line) >= 4 and line[3] == " ":
                break
            if len(line) < 4:
                break
        text = "\n".join(lines)
        print(f"[ftp] {text}")
        return int(lines[-1][:3]), text

    def cmd(self, c, hide=False):
        print(f"[ftp>] {'PASS ****' if hide else c}")
        self.sock.sendall((c + "\r\n").encode())
        return self.read_reply()

    def data_connect(self):
        code, text = self.cmd("EPSV")
        m = re.search(r"\(\|\|\|(\d+)\|\)", text)
        if code == 229 and m:
            dport = int(m.group(1))
        else:
            code, text = self.cmd("PASV")
            m = re.search(r"\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)", text)
            if not m:
                raise SystemExit("no PASV/EPSV port in reply")
            dport = int(m.group(5)) * 256 + int(m.group(6))
            # ignore advertised IP; always use the control host (tailnet IP)
        print(f"[ftp] data port = {dport} (via {self.host})")
        dsock, rest = connect_via_proxy(self.host, dport)
        return dsock, rest

    def list(self, path=""):
        dsock, rest = self.data_connect()
        code, _ = self.cmd(f"LIST {path}".strip())
        if code not in (150, 125):
            dsock.close()
            return code, ""
        data = rest or b""
        while True:
            chunk = dsock.recv(65536)
            if not chunk:
                break
            data += chunk
        dsock.close()
        self.read_reply()
        return code, data.decode("utf-8", "replace")

def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "100.108.36.18"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 4481
    user = os.environ.get("FTP_USER") or (sys.argv[3] if len(sys.argv) > 3 else "anonymous")
    password = os.environ.get("FTP_PASS") or (sys.argv[4] if len(sys.argv) > 4 else "anonymous@")
    ftp = FTP(host, port)
    code, _ = ftp.read_reply()
    if code != 220:
        raise SystemExit(f"unexpected banner code {code}")
    code, _ = ftp.cmd(f"USER {user}")
    if code == 331:
        code, _ = ftp.cmd(f"PASS {password}", hide=True)
    if code not in (230, 202):
        raise SystemExit(f"login failed: {code}")
    ftp.cmd("PWD")
    code, listing = ftp.list()
    print("=== LISTING / ===")
    print(listing if listing else "(empty)")
    ftp.cmd("QUIT")
    print("=== DONE ===")

if __name__ == "__main__":
    main()
