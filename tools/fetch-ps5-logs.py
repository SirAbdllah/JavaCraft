#!/usr/bin/env python3
"""Download JavaCraft diagnostic logs from the PS5 FTP server."""
import argparse
import ftplib
import pathlib
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="192.168.8.33")
    parser.add_argument("--port", type=int, default=2121)
    args = parser.parse_args()
    output = pathlib.Path(__file__).resolve().parent.parent / "build" / "ps5-logs"
    output.mkdir(parents=True, exist_ok=True)
    try:
        with ftplib.FTP() as ftp:
            ftp.connect(args.host, args.port, timeout=12)
            ftp.login()
            ftp.set_pasv(True)
            ftp.cwd("/data/prospero-win/logs")
            entries = [(name, facts) for name, facts in ftp.mlsd()
                       if facts.get("type") == "file" and
                       (name.startswith("session-") and name.endswith(".log") or name == "next.txt")]
            if not entries:
                raise RuntimeError("The PS5 log folder is empty.")
            for name, facts in sorted(entries):
                target = output / name
                with target.open("wb") as stream:
                    ftp.retrbinary(f"RETR {name}", stream.write)
                expected = int(facts.get("size", "-1"))
                if expected >= 0 and target.stat().st_size != expected:
                    raise IOError(f"The downloaded log {name} did not match the PS5 file size.")
                print(f"Downloaded {name} ({target.stat().st_size:,} bytes).", flush=True)
        print(f"Logs saved under: {output}")
        return 0
    except Exception as error:
        print(f"Could not download PS5 logs: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
