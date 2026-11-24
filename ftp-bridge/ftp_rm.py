#!/usr/bin/env python3
"""Delete all files in a folder on the Quest FTP server through the tunnel.
Usage: ftp_rm.py <remote_dir>
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ftp_test import FTP

def list_names(ftp, remote):
    dsock, rest = ftp.data_connect()
    code, _ = ftp.cmd(f"LIST {remote}")
    if code not in (150, 125):
        return []
    data = rest or b""
    while True:
        chunk = dsock.recv(65536)
        if not chunk:
            break
        data += chunk
    dsock.close()
    ftp.read_reply()
    names = []
    for line in data.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if len(parts) >= 9 and not line.startswith("d"):
            names.append(" ".join(parts[8:]))
    return names

def main():
    remote = sys.argv[1]
    ftp = FTP("100.108.36.18", 4481)
    code, _ = ftp.read_reply()
    assert code == 220, code
    code, _ = ftp.cmd(f"USER {os.environ['FTP_USER']}")
    if code == 331:
        code, _ = ftp.cmd(f"PASS {os.environ['FTP_PASS']}", hide=True)
    assert code == 230, code
    names = list_names(ftp, remote)
    print(f"{len(names)} files in {remote}")
    deleted = 0
    for n in names:
        code, _ = ftp.cmd(f"DELE {remote}/{n}")
        if code in (250, 200):
            deleted += 1
        else:
            print(f"DELE failed for {n}: {code}")
    print(f"deleted {deleted}")
    left = list_names(ftp, remote)
    print(f"remaining: {len(left)}")
    ftp.cmd("QUIT")

if __name__ == "__main__":
    main()
