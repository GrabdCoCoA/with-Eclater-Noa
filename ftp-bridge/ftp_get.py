#!/usr/bin/env python3
"""Download one file from the Quest FTP server through the tunnel.
Usage: ftp_get.py <remote_path> <local_path>
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ftp_test import FTP

def main():
    remote = sys.argv[1]
    local = sys.argv[2]
    ftp = FTP("100.108.36.18", 4481)
    code, _ = ftp.read_reply()
    assert code == 220, code
    code, _ = ftp.cmd(f"USER {os.environ['FTP_USER']}")
    if code == 331:
        code, _ = ftp.cmd(f"PASS {os.environ['FTP_PASS']}", hide=True)
    assert code == 230, code
    ftp.cmd("TYPE I")
    dsock, rest = ftp.data_connect()
    code, _ = ftp.cmd(f"RETR {remote}")
    if code not in (150, 125):
        print(f"RETR failed: {code}")
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
    with open(local, "wb") as f:
        f.write(data)
    print(f"saved {len(data)} bytes -> {local}")
    ftp.cmd("QUIT")

if __name__ == "__main__":
    main()
