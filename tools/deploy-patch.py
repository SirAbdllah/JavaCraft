#!/usr/bin/env python3
"""Safely stage and replace JavaCraft's bootstrap, wallpaper, and signed PS5 app over FTP."""
import argparse
import datetime
import ftplib
import hashlib
import pathlib
import posixpath
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="192.168.8.33")
    parser.add_argument("--port", type=int, default=2121)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    files = (
        (root / "dist/PS5/data/prospero-win/prefixes/minecraft-java/drive_c/JavaCraftPS5/bootstrap/javacraft-bootstrap.jar",
         "/data/prospero-win/prefixes/minecraft-java/drive_c/JavaCraftPS5/bootstrap/javacraft-bootstrap.jar"),
        (root / "artwork/javacraft-background.bgrx",
         "/data/homebrew/PPSA99995/sce_sys/javacraft-background.bgrx"),
        (root / "dist/PS5/data/homebrew/PPSA99995/eboot.bin",
         "/data/homebrew/PPSA99995/eboot.bin"),
    )
    for source, _ in files:
        if not source.is_file():
            raise SystemExit(f"Built JavaCraft file is missing: {source}")

    suffix = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    staged: list[tuple[pathlib.Path, str, str, str]] = []
    try:
        with ftplib.FTP() as ftp:
            ftp.connect(args.host, args.port, timeout=15)
            ftp.login()
            ftp.set_pasv(True)
            # ftpsrv decrypts SELF files on RETR by default. Turn that off so
            # stage and final SHA-256 checks verify the signed bytes on disk.
            ftp.sendcmd("SELF")
            ftp.voidcmd("TYPE I")
            for index, (source, remote) in enumerate(files):
                parent, name = posixpath.split(remote)
                ftp.cwd("/")
                for part in pathlib.PurePosixPath(parent).parts:
                    if part != "/":
                        ftp.cwd(part)
                # This FTP server aliases STOR names beginning with "eboot" to
                # its loaded ELF. Keep staging names generic, then rename only
                # after the uploaded bytes have been verified.
                temp = f"javacraft-stage-{index}-{suffix}.tmp"
                backup = f"javacraft-backup-{index}-{suffix}.bak"
                with source.open("rb") as stream:
                    ftp.storbinary(f"STOR {temp}", stream, blocksize=1024 * 1024)
                digest = hashlib.sha256()
                transferred = 0

                def hash_chunk(chunk: bytes) -> None:
                    nonlocal transferred
                    digest.update(chunk)
                    transferred += len(chunk)

                ftp.retrbinary(f"RETR {temp}", hash_chunk, blocksize=1024 * 1024)
                if transferred != source.stat().st_size or digest.hexdigest() != hashlib.sha256(source.read_bytes()).hexdigest():
                    raise IOError(f"PS5 did not verify the staged file {temp}.")
                staged.append((source, remote, temp, backup))

            for source, remote, temp, backup in staged:
                parent, name = posixpath.split(remote)
                ftp.cwd("/")
                for part in pathlib.PurePosixPath(parent).parts:
                    if part != "/":
                        ftp.cwd(part)
                old_saved = False
                try:
                    ftp.rename(name, backup)
                    old_saved = True
                except ftplib.error_perm as error:
                    if not str(error).startswith("550"):
                        raise
                try:
                    ftp.rename(temp, name)
                except Exception:
                    if old_saved:
                        ftp.rename(backup, name)
                    raise
                if remote == "/data/homebrew/PPSA99995/eboot.bin":
                    try:
                        # ftpsrv creates STOR uploads without execute permission.
                        # ShadowMountPlus cannot start the title until the ELF is executable.
                        ftp.sendcmd(f"SITE CHMOD 755 {remote}")
                    except Exception:
                        if old_saved:
                            failed = f"javacraft-unexecutable-{suffix}.bak"
                            ftp.rename(name, failed)
                            ftp.rename(backup, name)
                        raise
                note = f"previous file retained as {backup}" if old_saved else "new file installed"
                print(f"Updated {remote}; {note}.", flush=True)

            for source, remote, _, _ in staged:
                parent, name = posixpath.split(remote)
                ftp.cwd("/")
                for part in pathlib.PurePosixPath(parent).parts:
                    if part != "/":
                        ftp.cwd(part)
                digest = hashlib.sha256()
                transferred = 0

                def hash_installed(chunk: bytes) -> None:
                    nonlocal transferred
                    digest.update(chunk)
                    transferred += len(chunk)

                ftp.retrbinary(f"RETR {name}", hash_installed, blocksize=1024 * 1024)
                if transferred != source.stat().st_size or digest.hexdigest() != hashlib.sha256(source.read_bytes()).hexdigest():
                    raise IOError(f"PS5 did not verify the installed file {remote}.")
        print(f"JavaCraft patch uploaded to {args.host}. Restart the PS5 app to load it.")
        return 0
    except Exception as error:
        print(f"Upload stopped. Any backups already made stay on the PS5; staged temporary files may remain: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
