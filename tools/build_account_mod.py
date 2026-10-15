#!/usr/bin/env python3
"""Compile and package JavaCraft's in-game Microsoft account screen."""
import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import zipfile


MOD_FILE = "JavaCraft Account 1.0v.jar"
MOD_SOURCE = {
    "project_id": "JavaCraft Account",
    "name": "JavaCraft Account",
    "version": "1.0.2",
    "file": MOD_FILE,
    "source": "Personal JavaCraft PS5 client mod",
}


def run(command: list[str], cwd: pathlib.Path) -> None:
    result = subprocess.run(command, cwd=cwd, check=False)
    if result.returncode:
        raise RuntimeError(f"Build command failed with exit code {result.returncode}: {command[0]}")


def add_mod_entry(game: pathlib.Path, version: str) -> None:
    launch_paths = [game / "launches" / f"{version}.json"]
    if version == "26.3":
        launch_paths.append(game / "launch.json")
    for path in launch_paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        mods = [mod for mod in data.get("mods", []) if mod.get("file") != MOD_FILE]
        mods.append(MOD_SOURCE)
        data["mods"] = mods
        path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

    manifest_path = game / "mod-sources.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    mods = [mod for mod in manifest.get(version, []) if mod.get("file") != MOD_FILE]
    mods.append(MOD_SOURCE)
    manifest[version] = mods
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def build(project: pathlib.Path, game: pathlib.Path, javac: pathlib.Path,
          jar: pathlib.Path) -> pathlib.Path:
    source_root = project / "src" / "com" / "xxado" / "javacraft" / "account"
    resource_root = project / "tools" / "JavaCraftAccountMod" / "resources"
    source_files = sorted(source_root.glob("*.java"))
    for required in ("JavaCraftAccountMod.java", "MicrosoftAccountScreen.java", "MinecraftAuthAccessor.java"):
        if not (source_root / required).is_file():
            raise FileNotFoundError(f"In-game account source is missing: {source_root / required}")
    if not (resource_root / "fabric.mod.json").is_file():
        raise FileNotFoundError(f"Fabric metadata is missing: {resource_root}")

    scratch_root = project / "build" / "account-mod-compile"
    if scratch_root.exists():
        shutil.rmtree(scratch_root)
    scratch_root.mkdir(parents=True)
    bootstrap = game / "bootstrap" / "javacraft-bootstrap.jar"
    if not bootstrap.is_file():
        raise FileNotFoundError("The JavaCraft bootstrap is missing from the package.")
    versions = sorted(path.stem for path in (game / "launches").glob("*.json"))
    if not versions:
        raise FileNotFoundError("No staged Minecraft releases were found.")

    built: dict[str, pathlib.Path] = {}
    for version in versions:
        metadata = json.loads((game / "launches" / f"{version}.json").read_text(encoding="utf-8"))
        instance = game / "game" if version == "26.3" else game / "game" / "instances" / version
        fabric_api = sorted((instance / "mods").glob("fabric-api-*.jar"))
        if not fabric_api:
            raise FileNotFoundError(f"Fabric API is missing for Minecraft {version}.")
        mc_jar = game / "versions" / version / f"{version}.jar"
        if not mc_jar.is_file():
            raise FileNotFoundError(f"Minecraft {version} client jar is missing.")

        scratch = scratch_root / version
        classes = scratch / "classes"
        nested_libraries = scratch / "fabric-api-nested"
        classes.mkdir(parents=True)
        nested_libraries.mkdir(parents=True)
        nested_count = 0
        for api_jar in fabric_api:
            with zipfile.ZipFile(api_jar) as archive:
                for item in archive.infolist():
                    if (item.is_dir() or not item.filename.startswith("META-INF/jars/")
                            or not item.filename.endswith(".jar")):
                        continue
                    target = nested_libraries / pathlib.PurePosixPath(item.filename).name
                    target.write_bytes(archive.read(item))
                    nested_count += 1
        if nested_count == 0:
            raise RuntimeError(f"Fabric API for Minecraft {version} has no nested compile libraries.")

        libraries = [game / "libraries" / relative for relative in metadata.get("libraries", [])]
        missing = [path for path in libraries if not path.is_file()]
        if missing:
            raise FileNotFoundError(f"A Minecraft {version} compile dependency is missing: {missing[0]}")
        libraries += list(nested_libraries.glob("*.jar"))
        classpath = os.pathsep.join(
            str(path.resolve()).replace("\\", "/")
            for path in [mc_jar, bootstrap, *libraries])
        argfile = scratch / "javac.args"
        arguments = ["-encoding", "UTF-8", "-classpath", classpath, "-d", str(classes),
                     *(str(path) for path in source_files)]
        # Keep Windows' 32K CreateProcess limit out of a large Minecraft class path.
        argfile.write_text("\n".join('"' + value.replace("\\", "/").replace('"', '\\"') + '"'
                                      for value in arguments), encoding="utf-8")
        run([str(javac), "@" + str(argfile)], project)

        target = instance / "mods" / MOD_FILE
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            target.unlink()
        run([str(jar), "--create", "--file", str(target), "-C", str(classes), ".",
             "-C", str(resource_root), "."], project)
        modpack = game / "modpacks" / version
        modpack.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, modpack / target.name)
        add_mod_entry(game, version)
        built[version] = target
        print(f"Built {MOD_FILE} for Minecraft {version} ({target.stat().st_size:,} bytes).")
    return built.get("26.3", built[versions[-1]])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=pathlib.Path, required=True)
    parser.add_argument("--game", type=pathlib.Path, required=True)
    parser.add_argument("--javac", type=pathlib.Path, required=True)
    parser.add_argument("--jar", type=pathlib.Path, required=True)
    args = parser.parse_args()
    try:
        project = args.project.resolve()
        target = build(project, args.game.resolve(), args.javac.resolve(), args.jar.resolve())
        vendor = project / "tools" / "vendor" / MOD_FILE
        vendor.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(target, vendor)
        return 0
    except Exception as error:
        print(f"JavaCraft account mod build failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
