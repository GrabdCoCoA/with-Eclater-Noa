#!/usr/bin/env python3
"""Upload one file to the Quest FTP server through the tunnel, then verify.
Usage: ftp_put.py <local_path> <remote_path>
"""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ftp_test import FTP

def main():
    local = sys.argv[1]
    remote = sys.argv[2]
    with open(local, "rb") as f:
        data = f.read()
    ftp = FTP("100.108.36.18", 4481)
    code, _ = ftp.read_reply()
    assert code == 220, code
    code, _ = ftp.cmd(f"USER {os.environ['FTP_USER']}")
    if code == 331:
        code, _ = ftp.cmd(f"PASS {os.environ['FTP_PASS']}", hide=True)
    assert code == 230, code
    ftp.cmd("TYPE I")
    dsock, _rest = ftp.data_connect()
    code, _ = ftp.cmd(f"STOR {remote}")
    if code not in (150, 125):
        print(f"STOR failed: {code}")
        dsock.close()
        return
    dsock.sendall(data)
    dsock.close()
    code, text = ftp.read_reply()
    print(f"upload reply: {code} {text}")
    print(f"sent {len(data)} bytes -> {remote}")
    # verify with SIZE and a fresh listing of the parent dir
    ftp.cmd(f"SIZE {remote}")
    parent = remote.rsplit("/", 1)[0] or "/"
    code, listing = ftp.list(parent)
    print(f"=== LIST {parent} ===")
    print(listing)
    ftp.cmd("QUIT")

if __name__ == "__main__":
    main()
