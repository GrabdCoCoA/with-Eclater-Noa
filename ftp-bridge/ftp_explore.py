#!/usr/bin/env python3
"""Explore the Quest FTP tree: list a few key directories (read-only)."""
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ftp_test import FTP

def main():
    host = "100.108.36.18"
    port = 4481
    user = os.environ["FTP_USER"]
    password = os.environ["FTP_PASS"]
    ftp = FTP(host, port)
    code, _ = ftp.read_reply()
    assert code == 220, code
    code, _ = ftp.cmd(f"USER {user}")
    if code == 331:
        code, _ = ftp.cmd(f"PASS {password}", hide=True)
    assert code == 230, code
    for path in ["/device", "/device/Download", "/device/Oculus",
                 "/device/Oculus/Screenshots", "/device/Oculus/VideoShots",
                 "/device/Pictures", "/device/DCIM"]:
        print(f"===== LIST {path} =====")
        try:
            code, listing = ftp.list(path)
            print(f"[result code {code}]")
            print(listing if listing.strip() else "(empty or not found)")
        except SystemExit as e:
            print(f"[failed: {e}]")
    ftp.cmd("QUIT")
    print("=== DONE ===")

if __name__ == "__main__":
    main()
