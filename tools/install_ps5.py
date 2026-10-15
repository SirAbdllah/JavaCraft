#!/usr/bin/env python3
"""Install the personal JavaCraft PS5 build over the console's FTP service."""
import argparse
import ftplib
import io
import os
import pathlib
import re
import subprocess
import sys
import time


TITLE_ID = "PPSA99995"


def ftp_directory(ftp: ftplib.FTP, absolute: str, create: bool) -> None:
    ftp.cwd("/")
    for part in pathlib.PurePosixPath(absolute).parts:
        if part == "/":
            continue
        if create:
            try:
                ftp.mkd(part)
            except ftplib.error_perm:
                pass
        ftp.cwd(part)


def disable_self_conversion(ftp: ftplib.FTP) -> None:
    # ftpsrv can rewrite SELF executables unless its per-connection conversion
    # mode is disabled. Toggle only until the server confirms the safe state.
    for _ in range(2):
        try:
            response = ftp.sendcmd("SELF")
        except ftplib.error_perm:
            return
        if "disabled" in response.lower():
            return


def remote_size(ftp: ftplib.FTP, path: str):
    try:
        ftp_directory(ftp, str(pathlib.PurePosixPath(path).parent), False)
        return ftp.size(pathlib.PurePosixPath(path).name)
    except ftplib.all_errors:
        return None


def upload_tree(ftp: ftplib.FTP, source: pathlib.Path, destination: str) -> int:
    sent = 0
    ftp_directory(ftp, destination, True)
    for root, directories, files in os.walk(source, followlinks=False):
        root_path = pathlib.Path(root)
        relative = root_path.relative_to(source)
        remote_dir = pathlib.PurePosixPath(destination, *relative.parts).as_posix()
        ftp_directory(ftp, remote_dir, True)
        for name in sorted(files):
            local = root_path / name
            if local.is_symlink():
                continue
            remote = pathlib.PurePosixPath(remote_dir, name).as_posix()
            ftp_directory(ftp, remote_dir, False)
            with local.open("rb") as stream:
                ftp.storbinary(f"STOR {name}", stream, blocksize=1024 * 1024)
            sent += local.stat().st_size
    return sent


def retrieve_text(ftp: ftplib.FTP, path: str) -> str:
    data = bytearray()
    ftp_directory(ftp, str(pathlib.PurePosixPath(path).parent), False)
    ftp.retrbinary(f"RETR {pathlib.PurePosixPath(path).name}", data.extend)
    return data.decode("utf-8-sig")


def ensure_profile_listing(ftp: ftplib.FTP) -> None:
    path = "/data/prospero-win/profiles/profiles.lst"
    try:
        listing = retrieve_text(ftp, path)
    except ftplib.all_errors:
        listing = ""
    names = [line.strip() for line in listing.splitlines() if line.strip()]
    if "minecraft-java.profile" in names:
        return
    names.append("minecraft-java.profile")
    content = ("\n".join(names) + "\n").encode("ascii")
    ftp_directory(ftp, "/data/prospero-win/profiles", False)
    ftp.storbinary("STOR profiles.lst", io.BytesIO(content))


def ensure_cryptbase(ftp: ftplib.FTP, app: pathlib.Path) -> None:
    source = app / "win" / "wine" / "lib" / "wine" / "x86_64-windows" / "cryptbase.dll"
    if not source.is_file():
        raise RuntimeError(f"Wine cryptbase.dll is missing from the app runtime: {source}")
    target = "/data/prospero-win/prefixes/minecraft-java/drive_c/windows/system32/cryptbase.dll"
    source_bytes = source.read_bytes()
    source_path = pathlib.PurePosixPath(target)
    ftp_directory(ftp, str(source_path.parent), False)
    matches = False
    try:
        if ftp.size(source_path.name) == len(source_bytes):
            remote = bytearray()
            ftp.retrbinary(f"RETR {source_path.name}", remote.extend)
            matches = remote == source_bytes
    except ftplib.all_errors:
        pass
    if not matches:
        with source.open("rb") as stream:
            ftp.storbinary(f"STOR {source_path.name}", stream)
        remote = bytearray()
        ftp.retrbinary(f"RETR {source_path.name}", remote.extend)
        if remote != source_bytes:
            raise RuntimeError("The Wine cryptbase.dll copy in the PS5 prefix did not verify.")


def update_allowlist(text: str, enable_app_jailbreak: bool = True) -> str:
    lines = text.splitlines()
    section_start = next((i for i, line in enumerate(lines)
                          if line.strip().lower() == "[app_jailbreak]"), None)
    if section_start is None:
        if lines and lines[-1].strip():
            lines.append("")
        lines.append("[app_jailbreak]")
        if enable_app_jailbreak:
            lines.append("enabled=true")
        lines.append(f"exact_title_ids={TITLE_ID}")
        return "\n".join(lines) + "\n"

    section_end = next((i for i in range(section_start + 1, len(lines))
                        if re.fullmatch(r"\s*\[[^]]+\]\s*", lines[i])), len(lines))
    enabled_index = None
    ids_index = None
    ids = []
    for index in range(section_start + 1, section_end):
        match = re.match(r"\s*([a-z_]+)\s*=\s*(.*?)\s*$", lines[index], re.IGNORECASE)
        if not match:
            continue
        key, value = match.group(1).lower(), match.group(2)
        if key == "enabled":
            enabled_index = index
        elif key == "exact_title_ids":
            ids_index = index
            ids = [item.strip().upper() for item in value.split(",")
                   if item.strip() and item.strip().lower() != "none"]

    if TITLE_ID not in ids:
        ids.append(TITLE_ID)
    if len(ids) > 20:
        raise ValueError("The HEN already has 20 exact app IDs; PPSA99995 was not added.")
    if enable_app_jailbreak:
        if enabled_index is None:
            lines.insert(section_start + 1, "enabled=true")
            section_end += 1
            ids_index = ids_index + 1 if ids_index is not None else None
        else:
            lines[enabled_index] = re.sub(r"=.*$", "=true", lines[enabled_index])
    if ids_index is None:
        lines.insert(section_end, "exact_title_ids=" + ",".join(ids))
    else:
        lines[ids_index] = re.sub(r"=.*$", "=" + ",".join(ids), lines[ids_index])
    return "\n".join(lines) + "\n"


def save_config(ftp: ftplib.FTP, path: str, text: str) -> None:
    backup = path + ".javacraft-backup"
    if remote_size(ftp, backup) is None:
        ftp_directory(ftp, str(pathlib.PurePosixPath(path).parent), False)
        with io.BytesIO(text.encode("utf-8")) as stream:
            ftp.storbinary(f"STOR {pathlib.PurePosixPath(backup).name}", stream)
        if retrieve_text(ftp, backup) != text:
            raise RuntimeError("The original OnionHEN config backup could not be verified.")

    temp = path + ".javacraft-tmp"
    ftp_directory(ftp, str(pathlib.PurePosixPath(path).parent), False)
    with io.BytesIO(text.encode("utf-8")) as stream:
        ftp.storbinary(f"STOR {pathlib.PurePosixPath(temp).name}", stream)
    try:
        ftp.rename(pathlib.PurePosixPath(temp).name, pathlib.PurePosixPath(path).name)
    except ftplib.all_errors:
        with io.BytesIO(text.encode("utf-8")) as stream:
            ftp.storbinary(f"STOR {pathlib.PurePosixPath(path).name}", stream)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="192.168.8.33")
    parser.add_argument("--port", type=int, default=2121)
    parser.add_argument("--app", type=pathlib.Path, required=True)
    parser.add_argument("--library", type=pathlib.Path, required=True)
    parser.add_argument("--pw-prefix", type=pathlib.Path, required=True)
    parser.add_argument("--cpu-dll", type=pathlib.Path, required=True)
    args = parser.parse_args()
    app = args.app.resolve()
    library = args.library.resolve()
    if app.name != TITLE_ID or not (app / "eboot.bin").is_file():
        raise SystemExit(f"Expected the built {TITLE_ID} app folder: {app}")
    for path in (library / "prefixes/minecraft-java",
                 library / "profiles/minecraft-java.profile",
                 args.cpu_dll.resolve()):
        if not path.exists():
            raise SystemExit(f"Required staged file is missing: {path}")

    try:
        command = [sys.executable, str(args.pw_prefix.resolve()), "push", "minecraft-java",
                   "--library", str(library), "--host", args.host, "--port", str(args.port),
                   "--cpu-dll", str(args.cpu_dll.resolve())]
        subprocess.run(command, check=True)
        print("Minecraft profile and Wine prefix uploaded.", flush=True)

        with ftplib.FTP() as ftp:
            ftp.connect(args.host, args.port, timeout=8)
            ftp.login()
            ftp.set_pasv(True)
            disable_self_conversion(ftp)
            ftp.voidcmd("TYPE I")
            print(f"Connected to PS5 FTP at {args.host}:{args.port}.", flush=True)
            ensure_profile_listing(ftp)
            byte_count = upload_tree(ftp, app, f"/data/homebrew/{TITLE_ID}")
            print(f"PS5 app files uploaded ({byte_count:,} bytes).", flush=True)
            ensure_cryptbase(ftp, app)
            print("Wine cryptbase.dll installed and verified in the game prefix.", flush=True)
            for root, _, files in os.walk(app, followlinks=False):
                for name in files:
                    local = pathlib.Path(root) / name
                    relative = local.relative_to(app)
                    if name.lower() != "eboot.bin" and local.suffix.lower() != ".prx":
                        continue
                    remote_dir = pathlib.PurePosixPath(
                        f"/data/homebrew/{TITLE_ID}", *relative.parent.parts).as_posix()
                    try:
                        ftp_directory(ftp, remote_dir, False)
                        ftp.sendcmd(f"SITE CHMOD 755 {name}")
                    except ftplib.all_errors:
                        pass

            configured_hens = []
            for config_path, enable_jailbreak, hen_name in (
                ("/data/OnionHEN/config.ini", True, "OnionHEN"),
                ("/data/etaHEN/config.ini", False, "etaHEN"),
            ):
                try:
                    original = retrieve_text(ftp, config_path)
                except ftplib.all_errors:
                    continue
                updated = update_allowlist(original, enable_app_jailbreak=enable_jailbreak)
                if updated != original:
                    save_config(ftp, config_path, original)
                    save_config(ftp, config_path, updated)
                    print(f"{hen_name} now allows JavaCraft PS5 (PPSA99995).", flush=True)
                else:
                    print(f"{hen_name} already allows JavaCraft PS5.", flush=True)
                configured_hens.append((config_path, hen_name))
            if not configured_hens:
                print("No OnionHEN or etaHEN config was found. Add PPSA99995 to your HEN's app-jailbreak list if required.", flush=True)
            time.sleep(3)

            if remote_size(ftp, f"/data/homebrew/{TITLE_ID}/eboot.bin") is None:
                raise RuntimeError("The PS5 app eboot.bin could not be verified over FTP.")
            for config_path, hen_name in configured_hens:
                if TITLE_ID not in retrieve_text(ftp, config_path):
                    raise RuntimeError(f"The {hen_name} allowlist did not retain PPSA99995.")
            print("Install verified. Reload your HEN if needed, then refresh the homebrew app list.")
            return 0
    except Exception as error:
        print(f"Install stopped: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
