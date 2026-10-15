#!/usr/bin/env python3
"""Update JavaCraft's PS5 home-screen artwork, selection music, and profile."""
import argparse
import ftplib
import pathlib
import posixpath
import sys


def enter_directory(ftp: ftplib.FTP, path: str, create: bool) -> None:
    ftp.cwd("/")
    for part in pathlib.PurePosixPath(path).parts:
        if part == "/":
            continue
        if create:
            try:
                ftp.mkd(part)
            except ftplib.error_perm:
                pass
        ftp.cwd(part)


def upload(ftp: ftplib.FTP, source: pathlib.Path, remote: str) -> None:
    if not source.is_file():
        raise FileNotFoundError(f"Required JavaCraft app file is missing: {source}")
    parent, name = posixpath.split(remote)
    enter_directory(ftp, parent, True)
    with source.open("rb") as stream:
        ftp.storbinary(f"STOR {name}", stream, blocksize=1024 * 1024)
    size = ftp.size(name)
    if size != source.stat().st_size:
        raise IOError(f"PS5 did not save {source.name} at the expected size.")
    print(f"Uploaded {source.name} ({size:,} bytes).", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="192.168.8.33")
    parser.add_argument("--port", type=int, default=2121)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    app = root / "dist" / "PS5" / "data" / "homebrew" / "PPSA99995"
    sysdir = app / "sce_sys"
    profile = root / "dist" / "PS5" / "data" / "prospero-win" / "profiles" / "minecraft-java.profile"
    files = (
        (sysdir / "icon0.png", "/data/homebrew/PPSA99995/sce_sys/icon0.png"),
        (sysdir / "pic0.dds", "/data/homebrew/PPSA99995/sce_sys/pic0.dds"),
        (sysdir / "pic1.dds", "/data/homebrew/PPSA99995/sce_sys/pic1.dds"),
        (sysdir / "snd0.at9", "/data/homebrew/PPSA99995/sce_sys/snd0.at9"),
        (sysdir / "param.json", "/data/homebrew/PPSA99995/sce_sys/param.json"),
        (profile, "/data/prospero-win/profiles/minecraft-java.profile"),
    )
    try:
        with ftplib.FTP() as ftp:
            ftp.connect(args.host, args.port, timeout=15)
            ftp.login()
            ftp.set_pasv(True)
            ftp.voidcmd("TYPE I")
            for source, remote in files:
                upload(ftp, source, remote)
        return 0
    except Exception as error:
        print(f"JavaCraft home-screen update stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
