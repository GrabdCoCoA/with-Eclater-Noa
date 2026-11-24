#!/usr/bin/env python3
"""List a folder on the Quest FTP server through the tunnel.
Usage: ftp_ls.py <remote_dir>
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ftp_test import FTP

def main():
    remote = sys.argv[1] if len(sys.argv) > 1 else "/device"
    ftp = FTP("100.108.36.18", 4481)
    code, _ = ftp.read_reply()
    assert code == 220, code
    code, _ = ftp.cmd(f"USER {os.environ['FTP_USER']}")
    if code == 331:
        code, _ = ftp.cmd(f"PASS {os.environ['FTP_PASS']}", hide=True)
    assert code == 230, code
    dsock, rest = ftp.data_connect()
    code, _ = ftp.cmd(f"LIST {remote}")
    if code not in (150, 125):
        print(f"LIST failed: {code}")
        dsock.close()
        return
    data = rest or b""
    while True:
        chunk = dsock.recv(65536)
        if not chunk:
            break
        data += chunk
    dsock.close()
    ftp.read_reply()
    print(data.decode("utf-8", "replace"))
    ftp.cmd("QUIT")

if __name__ == "__main__":
    main()
