#!/usr/bin/env python3
"""Incrementally update JavaCraft's game files without touching worlds or settings."""
import argparse
import ftplib
import os
import pathlib
import posixpath
import sys
import time


REMOTE_GAME_ROOT = "/data/prospero-win/prefixes/minecraft-java/drive_c/JavaCraftPS5"
OLD_CONTROLIFY_NAMES = (
    "controlify-3.5.3-ps5fix1+mc26.3-fabric.jar",
    "controlify-3.5.3-ps5fix2+mc26.3-fabric.jar",
    "controlify-3.5.3-ps5fix3+mc26.3-fabric.jar",
    "controlify-3.5.3-ps5fix4+mc26.3-fabric.jar",
    "controlify-3.5.3-ps5fix5+mc26.3-fabric.jar",
    "controlify-3.5.3-ps5fix6+mc26.3-fabric.jar",
    "controlify-3.5.3+mc26.3-universal.jar",
)
GAME_MOD_DIRS = ("game/mods", "game/instances/26.1.1/mods",
                 "game/instances/26.1.2/mods", "game/instances/26.2/mods")


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


def remote_size(ftp: ftplib.FTP, path: str):
    parent, name = posixpath.split(path)
    try:
        enter_directory(ftp, parent, False)
        size = ftp.size(name)
        # PS5 ftpsrv reports a missing file as UINT64_MAX instead of raising 550.
        return None if size is None or size >= (1 << 64) - 1 else size
    except ftplib.all_errors:
        return None


def upload_tree(ftp: ftplib.FTP, source: pathlib.Path,
                only_files: set[str] | None = None) -> tuple[int, int]:
    sent_files = sent_bytes = 0
    for root, _, filenames in os.walk(source, followlinks=False):
        root_path = pathlib.Path(root)
        relative = root_path.relative_to(source)
        remote_dir = posixpath.join(REMOTE_GAME_ROOT, *relative.parts) if relative.parts else REMOTE_GAME_ROOT
        enter_directory(ftp, remote_dir, True)
        for filename in sorted(filenames):
            local = root_path / filename
            if local.is_symlink():
                continue
            relative_file = local.relative_to(source).as_posix()
            if only_files is not None and relative_file not in only_files:
                continue
            remote = posixpath.join(REMOTE_GAME_ROOT, relative_file)
            size = local.stat().st_size
            force = relative_file in {
                "launch.json", "mod-sources.json", "microsoft-client-id.txt",
                "bootstrap/javacraft-bootstrap.jar",
                "game/mods/JavaCraft Account 1.0v.jar",
                "game/mods/Controlify JavaCraft 1.0v.jar",
                "game/instances/26.1.1/mods/JavaCraft Account 1.0v.jar",
                "game/instances/26.1.2/mods/JavaCraft Account 1.0v.jar",
                "game/instances/26.2/mods/JavaCraft Account 1.0v.jar",
            } or relative_file.startswith("launches/")
            if not force and remote_size(ftp, remote) == size:
                continue
            parent, remote_name = posixpath.split(remote)
            enter_directory(ftp, parent, True)
            if only_files is None:
                with local.open("rb") as stream:
                    ftp.storbinary(f"STOR {remote_name}", stream, blocksize=1024 * 1024)
                if remote_size(ftp, remote) != size:
                    raise RuntimeError(f"The PS5 did not store {relative_file} at the expected size.")
            else:
                # Focused updates go through a temporary file and preserve the
                # previous copy, so interrupted mod/manifest uploads are recoverable.
                temp_name = remote_name + ".javacraft-uploading"
                backup_name = remote_name + f".previous-{time.time_ns()}"
                try:
                    with local.open("rb") as stream:
                        ftp.storbinary(f"STOR {temp_name}", stream, blocksize=1024 * 1024)
                    if remote_size(ftp, posixpath.join(parent, temp_name)) != size:
                        raise RuntimeError(f"The PS5 did not verify staged file {relative_file}.")
                    had_previous = remote_size(ftp, remote) is not None
                    if had_previous:
                        ftp.rename(remote_name, backup_name)
                    try:
                        ftp.rename(temp_name, remote_name)
                    except Exception:
                        if had_previous:
                            ftp.rename(backup_name, remote_name)
                        raise
                    if remote_size(ftp, remote) != size:
                        raise RuntimeError(f"The PS5 did not install {relative_file} at the expected size.")
                except Exception:
                    try:
                        ftp.delete(temp_name)
                    except ftplib.all_errors:
                        pass
                    raise
            sent_files += 1
            sent_bytes += size
            if sent_files % 100 == 0:
                print(f"Uploaded {sent_files} changed files ({sent_bytes:,} bytes).", flush=True)
    return sent_files, sent_bytes


def deactivate_previous_controlify(ftp: ftplib.FTP) -> int:
    new_mod = posixpath.join(REMOTE_GAME_ROOT, "game/mods/Controlify JavaCraft 1.0v.jar")
    if remote_size(ftp, new_mod) is None:
        raise RuntimeError("The updated PS5 controller mod is missing; leaving old files untouched.")
    renamed = 0
    for directory in GAME_MOD_DIRS:
        for filename in OLD_CONTROLIFY_NAMES:
            old = posixpath.join(REMOTE_GAME_ROOT, directory, filename)
            if remote_size(ftp, old) is None:
                continue
            parent, name = posixpath.split(old)
            enter_directory(ftp, parent, False)
            disabled = name + ".disabled"
            if remote_size(ftp, posixpath.join(parent, disabled)) is None:
                ftp.rename(name, disabled)
            else:
                # Keep any pre-existing backup and still make sure this active
                # old jar cannot be discovered as a Fabric mod.
                inactive = name + ".inactive"
                suffix = 2
                while remote_size(ftp, posixpath.join(parent, inactive)) is not None:
                    inactive = name + f".inactive{suffix}"
                    suffix += 1
                ftp.rename(name, inactive)
            renamed += 1
    return renamed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, required=True,
                        help="Generated JavaCraftPS5 game directory on this PC")
    parser.add_argument("--host", default="192.168.8.33")
    parser.add_argument("--port", type=int, default=2121)
    parser.add_argument("--only", action="append", dest="only_files",
                        help="upload only this package-relative file; repeat to select multiple files")
    args = parser.parse_args()
    source = args.source.expanduser().resolve()
    if not (source / "launch.json").is_file() or not (source / "bootstrap/javacraft-bootstrap.jar").is_file():
        raise SystemExit(f"The generated JavaCraft game package is incomplete: {source}")

    try:
        with ftplib.FTP() as ftp:
            ftp.connect(args.host, args.port, timeout=12)
            ftp.login()
            ftp.set_pasv(True)
            ftp.voidcmd("TYPE I")
            files, byte_count = upload_tree(ftp, source,
                                            set(args.only_files) if args.only_files else None)
            # A focused upload (for example, a single client mod) must not
            # make unrelated controller-mod changes on the console.
            renamed = deactivate_previous_controlify(ftp) if args.only_files is None else 0
            print(f"Updated JavaCraft on {args.host}: {files} changed files, "
                  f"{byte_count:,} bytes uploaded, {renamed} older Controlify files disabled.")
            if args.only_files is not None:
                print("Focused upload selected; all unrelated game mods were left alone.")
            print("Existing worlds, screenshots, settings, and account data were not deleted or replaced.")
        return 0
    except Exception as error:
        print(f"JavaCraft game update stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
