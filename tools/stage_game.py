#!/usr/bin/env python3
"""Stage supported Minecraft Java releases from this PC's official launcher data."""
import argparse
import hashlib
import json
import os
import pathlib
import platform
import re
import shutil
import urllib.parse
import urllib.request
import zipfile

SUPPORTED_VERSIONS = ("26.1.1", "26.1.2", "26.2", "26.3")
CONTROLIFY_PATCH_FILE = "Controlify JavaCraft 1.0v.jar"
CONTROLIFY_PATCH_SHA512 = "70d97e23d207a6550e8f98d3e8592ab1b119bc1bac4855897f4d8708e03022ba28917e0cc34e102ea33a98797e4815b032e1d24e2a4dc9d75d8a02afe528a48c"
CONTROLIFY_PATCH_VERSION = "3.5.3-ps5fix6+mc26.3"
CONTROLIFY_SOURCE_URL = "https://github.com/dx-Arcus/Controlify-Enhanced/tree/b6fa38595b72bd9ab209859e2909247ef0fccdc0"


def digest(path: pathlib.Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def windows_rule_matches(rule: dict) -> bool:
    os_rule = rule.get("os", {})
    if os_rule.get("name", "windows") != "windows":
        return False
    arch = os_rule.get("arch")
    if arch == "x86":
        return False
    if arch and arch not in ("x86_64", "amd64"):
        return False
    version = os_rule.get("version")
    if version and not re.search(version, platform.version()):
        return False
    return True


def allowed(item: dict, features: dict | None = None) -> bool:
    rules = item.get("rules")
    if not rules:
        return True
    result = False
    for rule in rules:
        if not windows_rule_matches(rule):
            continue
        required = rule.get("features", {})
        if features is None and required:
            continue
        if any(features.get(key, False) != value for key, value in required.items()):
            continue
        result = rule.get("action") == "allow"
    return result


def expand(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return value
    if isinstance(value, dict) and "value" in value and allowed(value):
        return expand(value["value"])
    return []


def copy_verified(source: pathlib.Path, target: pathlib.Path, meta: dict | None = None):
    if not source.is_file():
        raise FileNotFoundError(f"Missing official Minecraft file: {source}")
    if meta:
        expected_size = meta.get("size")
        expected_sha1 = meta.get("sha1")
        expected_sha512 = meta.get("sha512")
        if expected_size is not None and source.stat().st_size != expected_size:
            raise ValueError(f"Size check failed: {source}")
        if expected_sha1 and digest(source, "sha1") != expected_sha1.lower():
            raise ValueError(f"SHA-1 check failed: {source}")
        if expected_sha512 and digest(source, "sha512") != expected_sha512.lower():
            raise ValueError(f"SHA-512 check failed: {source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def fetch_json(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": "JavaCraftPS5/1.0 (personal launcher)"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def download_verified(url: str, target: pathlib.Path, meta: dict):
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "JavaCraftPS5/1.0 (personal launcher)"})
    with urllib.request.urlopen(request, timeout=120) as response:
        with target.open("wb") as output:
            shutil.copyfileobj(response, output)
    if meta.get("size") is not None and target.stat().st_size != meta["size"]:
        raise ValueError(f"Download size check failed: {target.name}")
    expected = meta.get("sha512") or meta.get("sha1")
    if expected:
        algorithm = "sha512" if meta.get("sha512") else "sha1"
        if digest(target, algorithm) != expected.lower():
            target.unlink(missing_ok=True)
            raise ValueError(f"Download checksum check failed: {target.name}")


def maven_artifact_path(coordinate: str) -> pathlib.Path:
    parts = coordinate.split(":")
    if len(parts) not in (3, 4):
        raise ValueError(f"Unsupported Fabric library coordinate: {coordinate}")
    group, artifact, version = parts[:3]
    if "@" in version:
        version, extension = version.rsplit("@", 1)
    else:
        extension = "jar"
    classifier = f"-{parts[3]}" if len(parts) == 4 else ""
    filename = f"{artifact}-{version}{classifier}.{extension}"
    return pathlib.Path(*group.split(".")) / artifact / version / filename


def add_fabric_and_controller_mods(out: pathlib.Path, version: str, libraries: list[str],
                                   game_dir: pathlib.Path):
    profile = fetch_json(f"https://meta.fabricmc.net/v2/versions/loader/{version}/0.19.5/profile/json")
    if profile.get("mainClass") != "net.fabricmc.loader.impl.launch.knot.KnotClient":
        raise ValueError("Fabric metadata returned an unexpected client entry point.")
    for library in profile.get("libraries", []):
        relative = maven_artifact_path(library["name"])
        destination = out / "libraries" / relative
        if not destination.is_file():
            base_url = library.get("url", "https://maven.fabricmc.net/")
            download_verified(base_url.rstrip("/") + "/" + relative.as_posix(), destination, library)
        if relative.as_posix() not in libraries:
            libraries.append(relative.as_posix())

    source_records = []
    # Keep the existing controller/account stack and add optimizations that
    # cover both game simulation, memory use, and draw-call overhead. Avoid a
    # renderer replacement here: the PS5 Wine/Zink path is sensitive to GL mods.
    for project_id in ("DOUdJVEm", "1eAoo2KR", "P7dR8mSH",
                       "gvQqBUqZ", "uXXizFIs", "5ZwdcRci"):
        # The bootstrap uses <install>/game as Minecraft's game directory.
        # Fabric discovers client mods in that directory's mods subfolder.
        private_patch = pathlib.Path(__file__).resolve().parent / "vendor" / CONTROLIFY_PATCH_FILE
        if project_id == "DOUdJVEm" and version == "26.3" and private_patch.is_file():
            source = private_patch
            target = game_dir / "mods" / CONTROLIFY_PATCH_FILE
            copy_verified(source, target, {
                "size": 6503343, "sha512": CONTROLIFY_PATCH_SHA512
            })
            source_record = {
                "project_id": project_id,
                "name": "Controlify JavaCraft 1.0v",
                "version": CONTROLIFY_PATCH_VERSION,
                "file": CONTROLIFY_PATCH_FILE,
                "url": CONTROLIFY_SOURCE_URL,
                "sha512": CONTROLIFY_PATCH_SHA512,
            }
        else:
            encoded_version = json.dumps([version], separators=(",", ":"))
            query = f"?game_versions={urllib.parse.quote(encoded_version)}&loaders=%5B%22fabric%22%5D"
            versions = fetch_json(f"https://api.modrinth.com/v2/project/{project_id}/version{query}")
            selected = next((version for version in versions if version.get("version_type") == "release"), None)
            if not selected:
                raise ValueError(f"No stable Minecraft {version} Fabric release for mod project {project_id}.")
            file = next((item for item in selected["files"] if item.get("primary")), selected["files"][0])
            target = game_dir / "mods" / file["filename"]
            download_verified(file["url"], target, {
                "size": file.get("size"), "sha512": file["hashes"].get("sha512")
            })
            source_record = {
                "project_id": project_id,
                "name": selected["name"],
                "version": selected["version_number"],
                "file": file["filename"],
                "url": file["url"],
                "sha512": file["hashes"].get("sha512"),
            }
        source_records.append(source_record)
        with zipfile.ZipFile(target) as archive:
            for item in archive.infolist():
                base = pathlib.PurePosixPath(item.filename).name
                if item.is_dir() or not re.match(r"^(license|notice)(\.|$)", base.lower()):
                    continue
                destination = out / "licenses" / "mods" / f"{project_id}-{base}"
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(item))
    return profile, source_records


def stage_one(mc: pathlib.Path, out: pathlib.Path, version: str, java_runtime: pathlib.Path,
              written_natives: dict[str, str]):
    metadata_path = mc / "versions" / version / f"{version}.json"
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    if metadata.get("id") != version or metadata.get("mainClass") != "net.minecraft.client.main.Main":
        raise ValueError(f"Installed version metadata is not the expected Java client: {version}")

    client_rel = pathlib.Path("versions") / version / f"{version}.jar"
    copy_verified(mc / client_rel, out / client_rel, metadata.get("downloads", {}).get("client"))
    shutil.copy2(metadata_path, out / "versions" / version / metadata_path.name)

    libraries: list[str] = []
    native_jars: list[pathlib.Path] = []
    gson_path = None
    for library in metadata.get("libraries", []):
        if not allowed(library):
            continue
        downloads = library.get("downloads", {})
        artifact = downloads.get("artifact")
        coordinate = library.get("name", "")
        parts = coordinate.split(":")
        if len(parts) not in (3, 4):
            raise ValueError(f"Unsupported Minecraft library coordinate: {coordinate}")
        if len(parts) == 4 and parts[3] != "natives-windows":
            continue
        if artifact:
            relative = pathlib.Path(artifact["path"])
            copy_verified(mc / "libraries" / relative, out / "libraries" / relative, artifact)
            if len(parts) == 3:
                libraries.append(relative.as_posix())
        else:
            relative = maven_artifact_path(coordinate)
            copy_verified(mc / "libraries" / relative, out / "libraries" / relative)
            if len(parts) == 3:
                libraries.append(relative.as_posix())

        if coordinate.startswith("com.google.code.gson:gson:"):
            gson_path = relative.as_posix()
        native_name = library.get("natives", {}).get("windows")
        if native_name:
            classifier = native_name.replace("${arch}", "64")
            native = downloads.get("classifiers", {}).get(classifier)
            if native:
                native_relative = pathlib.Path(native["path"])
                copy_verified(mc / "libraries" / native_relative,
                              out / "libraries" / native_relative, native)
                native_jars.append(native_relative)
        elif len(parts) == 4 and parts[3] == "natives-windows":
            native_jars.append(relative)
    if not gson_path:
        raise ValueError(f"Minecraft {version} metadata has no Gson dependency.")

    native_root = out / "natives" / version
    for folder in ("java", "jna", "lwjgl", "netty"):
        (native_root / folder).mkdir(parents=True, exist_ok=True)
    for relative in native_jars:
        with zipfile.ZipFile(out / "libraries" / relative) as archive:
            for entry in archive.infolist():
                name = pathlib.PurePosixPath(entry.filename).name
                if entry.is_dir() or not name.lower().endswith(".dll"):
                    continue
                content = archive.read(entry)
                checksum = hashlib.sha256(content).hexdigest()
                if name in written_natives and written_natives[name] != checksum:
                    raise ValueError(f"Conflicting native library in Minecraft metadata: {name}")
                if name not in written_natives:
                    (native_root / name).write_bytes(content)
                    written_natives[name] = checksum

    asset_index_id = str(metadata["assetIndex"]["id"])
    index_path = mc / "assets" / "indexes" / f"{asset_index_id}.json"
    asset_index = json.loads(index_path.read_text(encoding="utf-8"))
    staged_index = out / "assets" / "indexes" / index_path.name
    staged_index.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(index_path, staged_index)
    for item in asset_index.get("objects", {}).values():
        sha1 = item["hash"]
        relative = pathlib.Path("objects") / sha1[:2] / sha1
        target = out / "assets" / relative
        if not target.is_file():
            copy_verified(mc / "assets" / relative, target, {"size": item.get("size"), "sha1": sha1})

    runtime_component = metadata.get("javaVersion", {}).get("component")
    if not runtime_component:
        raise ValueError(f"Minecraft {version} metadata does not identify its Java runtime.")
    javaw = java_runtime / "bin" / "javaw.exe"
    if not javaw.is_file():
        raise FileNotFoundError(f"Minecraft Java runtime not found: {javaw}")
    release_file = java_runtime / "release"
    if not release_file.is_file():
        raise FileNotFoundError(f"Java runtime release metadata not found: {release_file}")
    release_text = release_file.read_text(encoding="ascii", errors="replace")
    expected_major = str(metadata.get("javaVersion", {}).get("majorVersion", ""))
    match = re.search(r'^JAVA_VERSION="(\d+)', release_text, re.MULTILINE)
    if not match or match.group(1) != expected_major:
        raise ValueError(f"Minecraft {version} requires Java major {expected_major}: {release_file}")
    runtime_target = out / "runtime" / runtime_component
    if not runtime_target.exists():
        shutil.copytree(java_runtime, runtime_target)

    features = {
        "is_demo_user": False,
        "has_custom_resolution": True,
        "is_quick_play_singleplayer": False,
        "is_quick_play_multiplayer": False,
        "is_quick_play_realms": False,
        "has_quick_plays_support": False,
    }
    game_args = []
    for item in metadata.get("arguments", {}).get("game", []):
        if isinstance(item, dict):
            if allowed(item, features):
                game_args.extend(expand(item.get("value")))
        else:
            game_args.append(item)

    game_dir = out / "game" if version == "26.3" else out / "game" / "instances" / version
    game_dir.mkdir(parents=True, exist_ok=True)
    fabric, mod_sources = add_fabric_and_controller_mods(out, version, libraries, game_dir)
    manifests_dir = out / "manifests"
    manifests_dir.mkdir(parents=True, exist_ok=True)
    (manifests_dir / f"minecraft-{version}.json").write_text(
        json.dumps(metadata, separators=(",", ":")) + "\n", encoding="utf-8")
    (manifests_dir / f"fabric-{version}.json").write_text(
        json.dumps(fabric, separators=(",", ":")) + "\n", encoding="utf-8")

    # Keep only separately licensed Fabric mods in the public launcher payload.
    # Mojang's client, libraries, assets and player data are downloaded/created
    # on the console and are deliberately not part of this modpack directory.
    modpack_dir = out / "modpacks" / version
    modpack_dir.mkdir(parents=True, exist_ok=True)
    for mod in (game_dir / "mods").glob("*.jar"):
        shutil.copy2(mod, modpack_dir / mod.name)

    launch = {
        "id": version,
        "mainClass": fabric["mainClass"],
        "assetIndex": asset_index_id,
        "clientJar": client_rel.as_posix(),
        "libraries": libraries,
        "gsonJar": gson_path,
        "gameArgs": game_args,
        "fabricLoader": fabric["id"],
        "runtimeComponent": runtime_component,
        "mods": mod_sources,
    }
    launch_dir = out / "launches"
    launch_dir.mkdir(parents=True, exist_ok=True)
    (launch_dir / f"{version}.json").write_text(json.dumps(launch, indent=2) + "\n", encoding="utf-8")
    if version == "26.3":
        (out / "launch.json").write_text(json.dumps(launch, indent=2) + "\n", encoding="utf-8")
    print(f"Staged Minecraft Java {version} with Fabric {fabric['id']}: "
          f"{len(libraries)} libraries, {len(asset_index.get('objects', {}))} verified assets, "
          f"and {len(mod_sources)} controller/client mods.")


def stage(mc: pathlib.Path, out: pathlib.Path, java_runtime: pathlib.Path | None):
    marker = out / ".javacraft-managed"
    if out.exists():
        if not marker.is_file():
            raise RuntimeError(f"Refusing to replace an unmarked folder: {out}")
        shutil.rmtree(out)
    out.mkdir(parents=True)
    marker.write_text("Generated by JavaCraftPS5/tools/stage_game.py\n", encoding="ascii")

    versions = [version for version in SUPPORTED_VERSIONS
                if (mc / "versions" / version / f"{version}.json").is_file()
                and (mc / "versions" / version / f"{version}.jar").is_file()]
    if "26.3" not in versions:
        raise SystemExit(f"Minecraft Java 26.3 client is required under {mc}")
    runtime_source = java_runtime
    if runtime_source is None:
        runtime_component = json.loads((mc / "versions" / "26.3" / "26.3.json").read_text(
            encoding="utf-8")).get("javaVersion", {}).get("component")
        runtime_source = mc / "runtime" / "windows-x64" / runtime_component

    all_mod_sources = {}
    for version in versions:
        stage_one(mc, out, version, runtime_source, {})
        metadata = json.loads((out / "launches" / f"{version}.json").read_text(encoding="utf-8"))
        all_mod_sources[version] = metadata["mods"]
    (out / "mod-sources.json").write_text(json.dumps(all_mod_sources, indent=2) + "\n", encoding="utf-8")
    bootstrap = out / "bootstrap"
    bootstrap.mkdir(parents=True, exist_ok=True)
    launcher_artwork = pathlib.Path(__file__).resolve().parent.parent / "artwork"
    background = launcher_artwork / "minecraft-title-background.png"
    if background.is_file():
        shutil.copy2(background, bootstrap / background.name)

    # The pre-launch controller pointer uses the same SDL gamepad binding shipped with 26.3.
    for relative in (
        "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3.jar",
        "org/lwjgl/lwjgl-sdl/3.4.3/lwjgl-sdl-3.4.3.jar",
    ):
        dependency = out / "libraries" / pathlib.Path(relative)
        if not dependency.is_file():
            raise SystemExit(f"A launcher controller dependency is missing: {dependency}")
        shutil.copy2(dependency, bootstrap / dependency.name)

    print(f"Supported releases: {', '.join(versions)}")
    print("No launcher accounts, Microsoft tokens, saved worlds, screenshots, or settings were copied.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--minecraft", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    parser.add_argument("--java-runtime", type=pathlib.Path,
                        help="Java runtime directory matching the game's required major version")
    args = parser.parse_args()
    mc = args.minecraft.expanduser().resolve()
    out = args.out.expanduser().resolve()
    if not (mc / "versions" / "26.3" / "26.3.json").is_file():
        raise SystemExit(f"Minecraft Java 26.3 metadata not found under {mc}")
    stage(mc, out, args.java_runtime.expanduser().resolve() if args.java_runtime else None)


if __name__ == "__main__":
    main()
