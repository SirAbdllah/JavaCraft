#!/usr/bin/env python3
"""Stage the initialized Wine system files needed by the JavaCraft uploader."""
import argparse
import shutil
import sys
from pathlib import Path


def selected(key: str, registry: tuple[str, ...]) -> bool:
    return key in registry or key == "wineserver" or key == "dosdevices" \
        or key.startswith("dosdevices/") or key == "drive_c/windows" \
        or key.startswith("drive_c/windows/")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path)
    parser.add_argument("--upstream-tools", type=Path, required=True)
    parser.add_argument("--cpu-dll", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    sys.path.insert(0, str(args.upstream_tools.resolve()))
    try:
        import pw_prefix
    except Exception as error:
        raise SystemExit(f"Cannot load prospero-win prefix tools: {error}")

    source = args.source.resolve()
    required = (
        "system.reg", "user.reg", "userdef.reg", "wineserver",
        "drive_c/windows/system32/kernel32.dll",
        "drive_c/windows/system32/kernelbase.dll",
        "drive_c/windows/syswow64/kernel32.dll",
        "drive_c/windows/syswow64/kernelbase.dll",
        "drive_c/windows/winsxs/amd64_microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef/comctl32.dll",
    )
    missing = [relative for relative in required if not (source / relative).is_file()]
    cpu_dll = args.cpu_dll.resolve() if args.cpu_dll else None
    if not (source / pw_prefix.CPU_DLL).is_file() and not (cpu_dll and cpu_dll.is_file()):
        missing.append(pw_prefix.CPU_DLL)
    if missing:
        raise SystemExit("The initialized Wine prefix is incomplete: " + ", ".join(missing))
    if args.check:
        print("Initialized Wine prefix is ready.")
        return 0
    if args.target is None:
        raise SystemExit("--target is required unless --check is used")

    target = args.target.resolve()
    files, directories, links = pw_prefix.local_tree(source)
    selected_dirs = [key for key in directories if selected(key, pw_prefix.REGISTRY)]
    for key in sorted(selected_dirs):
        (target / key).mkdir(parents=True, exist_ok=True)

    staged = 0
    staged_bytes = 0
    for key, path in sorted(files.items()):
        if not selected(key, pw_prefix.REGISTRY):
            continue
        destination = target / key
        destination.parent.mkdir(parents=True, exist_ok=True)
        if key in pw_prefix.REGISTRY:
            data = pw_prefix.to_console(key, path.read_bytes())
            destination.write_bytes(data)
            staged_bytes += len(data)
        else:
            shutil.copy2(path, destination)
            staged_bytes += destination.stat().st_size
        staged += 1

    if not (target / pw_prefix.CPU_DLL).is_file() and cpu_dll:
        destination = target / pw_prefix.CPU_DLL
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(cpu_dll, destination)
        staged += 1
        staged_bytes += destination.stat().st_size

    table_count = 0
    for directory, table in sorted(links.items()):
        if not selected(directory, pw_prefix.REGISTRY):
            continue
        destination = target / directory / pw_prefix.LINK_TABLE
        destination.parent.mkdir(parents=True, exist_ok=True)
        text = "".join(f"{name}\t{link}\n" for name, link in sorted(table.items()))
        destination.write_text(text, encoding="utf-8", newline="")
        staged += 1
        staged_bytes += destination.stat().st_size
        table_count += 1

    if not (target / "drive_c/windows/system32/kernel32.dll").is_file():
        raise SystemExit("Wine prefix staging failed: kernel32.dll was not copied")
    print(f"Staged {staged:,} Wine files/links ({staged_bytes / (1 << 30):.2f} GiB) in {table_count} link tables.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
