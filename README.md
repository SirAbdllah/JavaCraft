# JavaCraft PS5

[![❤️ Donation](https://img.shields.io/badge/%E2%9D%A4_Donation-PayPal-ef4444?style=for-the-badge&logo=paypal&logoColor=white)](https://paypal.me/AAlqhtani581)
[![Current Update](https://img.shields.io/badge/Current_Update-JavaCraft_v1.0-2ea44f?style=for-the-badge&logo=github)](#javacraft-10--base-open-source-release)
[![JavaCraft Details](https://img.shields.io/badge/JavaCraft-Details-5865f2?style=for-the-badge&logo=minecraft&logoColor=white)](#how-it-works)
[![Support & Updates](https://img.shields.io/badge/Support_%26_Updates-BS6_Discord-5865f2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.com/BS6)

JavaCraft is an open-source launcher project by **V0BIT** for running a
player's own Minecraft Java Edition installation through the Prospero Win
homebrew runtime on a homebrew-enabled PS5. It is a compatibility launcher,
not a native PS5 port of Minecraft. JavaCraft is an unofficial community
project and is not affiliated with Mojang, Microsoft, or Sony.

## JavaCraft 1.0 — Base Open-Source Release

This is the initial JavaCraft 1.0 source release by **V0BIT**. It includes the
launcher, its PS5 uploader, the Black Shield Community client/server
components, and build instructions. It is intended for personal use, learning,
community contributions, and lawful modification under the licenses in this
repository.

### How it works

1. The PS5 homebrew app opens JavaCraft and reads the selected Minecraft
   version, player name, controller mode, and instance settings.
2. On first setup, JavaCraft obtains Minecraft Java files from Mojang's
   official version metadata and download services, verifies published file
   hashes, and stores game data in the selected profile.
3. Before launch, the JavaCraft bootstrap checks its HTTPS mod feed. It verifies
   downloaded mod sizes and SHA-256 hashes, installs changed mods, and starts
   Minecraft with the selected Java runtime, Fabric profile, and saved settings.
4. Minecraft Java Edition runs in the Prospero Win compatibility environment
   on PS5 homebrew. Minecraft itself is **not** a native PS5 port. Controller
   support comes from the Fabric/Controlify setup.
5. Sign-in uses Microsoft's device-code flow. Players enter credentials only
   on Microsoft's page; JavaCraft does not collect Microsoft passwords. The
   game session is used for the current process.
6. Black Shield Community is optional. Its Fabric client mod answers a BS6
   server handshake and the Paper/Purpur plugin applies the server gate. This
   marker is a practical client check, not hardware-backed proof that a client
   physically runs on a PS5; see the platform note in the BS6 guide.

The JavaCraft source license covers original JavaCraft code only. Minecraft,
Sony/PS5 components, runtime files, mods, and upstream projects retain their
own terms. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
before redistributing anything.

## What is included

- JavaCraft launcher, settings and first-run installer source.
- Fabric profile metadata and separate Fabric mod files for supported releases.
- Microsoft sign-in through Microsoft's official device-code page. The
  launcher never asks for or stores a Microsoft password.
- A public Microsoft Application (client) ID in the generated release
  configuration, so players do not have to enter it. Client IDs are public
  identifiers and can be read from a distributed app; they are not passwords
  or client secrets.
- Version-bound JavaCraft instances with separate worlds, options, configs and
  mod folders. The default instance keeps using the existing game folders.
- Optional **Black Shield Community** Fabric client mod and Paper server plugin
  source for the separate BS6 multiplayer service. Configure its HTTPS API and
  server plugin before use; see [black-shield-community/README.md](black-shield-community/README.md).

The first-run installer fetches the selected Minecraft version, libraries,
natives and assets from Mojang's official download metadata and verifies the
published SHA-1 hashes. It does not include a Minecraft client, game assets,
worlds, screenshots, account tokens, or personal settings in the source tree.
Players must use an account with a valid Minecraft Java Edition entitlement to
play online on authenticated servers.

## Automatic mod updates

Before each game launch, JavaCraft checks the HTTPS feed. It stages
changed mod JARs, checks their size and SHA-256, then installs them before
Minecraft starts. User-added mods are left alone. If the feed has not been
published or the service is offline, JavaCraft keeps the already installed
mods and continues launching.

To prepare the static feed, build the PS5 app folder and run
`python tools/build-mod-update-feed.py`. Upload the generated
`dist/JavaCraft-Update-Feed/javacraft/mod-updates` directory to the same path
under the website document root. The manifest must then return HTTP 200 at the
URL above. Keep this feed deployment separate from the clean GitHub source
archive: it contains mod JAR binaries, whose authors' redistribution terms
still apply. Do not upload Minecraft files, runtime files, or player data.

## Separate game instances

On the PS5 launcher, highlight **INSTANCE** and use left/right to switch
between profiles for the selected Minecraft version. Press **X** there to
create a profile and enter its name. A new profile gets its own game folder
and starts with a fresh worlds folder; JavaCraft copies the selected
profile's mods, configuration and `options.txt` so controller setup carries
over. It does not copy, move or overwrite existing worlds. The **MODS** page
shows the active profile's mod folder, and the launcher records its ID in
`launcher-settings.json` before starting Minecraft.

## Windows PS5 uploader

The optional C# desktop utility at `tools/JavaCraftUploader` has three actions:
manage user mods and worlds, install/repair the app-folder package, and
uninstall JavaCraft from a PS5 over local FTP. Connect checks the console first;
the install action changes to Repair when files are already present or
incomplete. Transfer, package scanning and cleanup run off the UI thread. Core
JavaCraft and Black Shield mods are locked in the mod manager. Full uninstall
deletes the JavaCraft app, emulator, Minecraft prefix, worlds, mods, runtime,
settings and account data. Shared Prospero Win files and other apps' profiles
are preserved. The uploader shows the v0bit credit and the
[BS6 Discord](https://discord.com/BS6). FTP is unencrypted and the utility only
accepts private IPv4 addresses.

The uploader detects the local `dist/PS5/data` package when run from the
project's `dist` folder. Its Windows release archive does not bundle Minecraft
client files, assets, PS5 runtime binaries, worlds, or account data; these files
have separate distribution terms. The uploader does not fetch or redistribute them. The
build stages an initialized Wine prefix from the Ubuntu WSL library, including
the Windows DLL trees and registry required to start Java. The uploader now
rejects packages missing those files instead of installing a launcher that
crashes on startup.

The app uses the shared `/download0/etahen_jailbreak` request and the Prospero
Win elevation fallback. The uploader adds title ID `PPSA99995` to an existing
OnionHEN or etaHEN config. With etaHEN, enable app-jailbreak for the title in
its settings/Toolbox when required, then reload etaHEN. Other HENs require a
compatible app-jailbreak handler and allowlist. This does not add a firmware
exploit; firmware 13.60 compatibility depends on the exact HEN build installed.

## Build

The PS5 app-folder build uses the pinned Prospero Win source and the PS5
homebrew toolchain. On Windows, prepare the owner's existing PS5 app folder,
an official Minecraft Java installation for build-time mod compilation, Python
3, JDK 25, .NET 8, and the initialized Wine prefix in the Ubuntu WSL staging
library. `tools/build.ps1` copies that prefix's system DLLs and registry into
the app-folder package. The provided public client ID is included in the launcher
and in-game account mod, so players are not asked to enter it. Set
`JAVACRAFT_MS_CLIENT_ID` only if you want to build with a different public ID.
Client IDs are public identifiers, not secrets, and cannot be hidden from app
players.

The generated app-folder tree is under `dist/PS5`. Community tools such as
[LibProsperoPkg](https://github.com/SvenGDK/LibProsperoPKG) can build debug
fPKG files for homebrew applications. However, JavaCraft does not yet have a
self-contained fPKG release: the current native app expects its large Wine
runtime and Minecraft prefix at `/data/prospero-win`, outside the title's
installed app folder. Packing only `eboot.bin` and `sce_sys` would install a
launcher that cannot start Minecraft on a clean console. Until a first-run
installer copies the bundled runtime into the required data location, use the
existing homebrew app-folder installation and transfer both `data/homebrew`
and `data/prospero-win` to the console. Do not rename a ZIP file to `.pkg`.

For GitHub, publish the source files only. Exclude `dist/`, `stage/`, `build/`,
`artwork/`, `tools/vendor/`, generated `.jar`/`.exe` files, and all Minecraft or
PS5 runtime files. `tools/package-source.ps1` creates a source-only ZIP with
these exclusions.

## Microsoft sign-in

Choose **JavaCraft Account** from the in-game menu, then choose **Sign in with
Microsoft**. JavaCraft displays Microsoft's one-time device code and
verification page. Complete password, email-code, Authenticator, or other
security checks on Microsoft's page. The session remains in memory for the
current game process. Email/password collection inside JavaCraft is purposely
not implemented.

## Licensing and redistribution

JavaCraft's original source code is licensed under MIT; see [LICENSE](LICENSE).
Black Shield Community has its own separate MIT license at
[`black-shield-community/LICENSE`](black-shield-community/LICENSE).
For original JavaCraft code, MIT permits use, modification, building, play,
redistribution, and commercial use, provided the copyright and license notice
are retained. It comes without warranty. That grant does not give rights to
Minecraft, Sony software, or third-party libraries and mods. Each dependency
keeps its own license; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
the licenses shipped with its upstream project. Minecraft is proprietary and
must be obtained through Mojang's official launcher/services. Do not upload
Minecraft client files, assets, JavaCraft users' world saves, or authentication
tokens to GitHub.

Everyone may inspect, modify, build, and play with the JavaCraft source under
the MIT terms, subject to the separate terms for Minecraft, PS5 homebrew, and
every included third-party project. The JavaCraft license cannot grant rights
on behalf of those projects.

## Acknowledgements and upstream sources

JavaCraft was built with help from the following projects, libraries, tools,
and documentation. These links identify upstream work used or integrated;
they do not mean JavaCraft owns or relicenses that work. Exact mod artifact
versions and hashes are recorded in generated `mod-sources.json` files, and
each upstream license remains in force.

- Prospero Win runtime and its app-folder format: [mpereiraesaa/prospero-win](https://github.com/mpereiraesaa/prospero-win).
- Windows compatibility layer used by the runtime: [Wine](https://gitlab.winehq.org/wine/wine), as incorporated and licensed by the selected Prospero Win build.
- PS5 OpenGL port and Native App Boilerplate: **BlackBearReloaded** —
  [ps5-opengl](https://github.com/blackbearreloaded/ps5-opengl).
- PS5 Mesa WGL/Zink work: **mihawk-99**, credited by Prospero Win —
  [prospero-win credits](https://github.com/mpereiraesaa/prospero-win#credits).
- HEN app-jailbreak integration: [etaHEN](https://github.com/etaHEN/etaHEN) and
  [OnionHEN](https://github.com/aydencharles/onionHEN).
- Minecraft mod loader and API: [Fabric](https://fabricmc.net/),
  [Fabric Loader](https://github.com/FabricMC/fabric-loader), and
  [Fabric API](https://github.com/FabricMC/fabric).
  The build obtains loader metadata from [Fabric Meta](https://meta.fabricmc.net/)
  and libraries from [Fabric Maven](https://maven.fabricmc.net/).
- Controller support: [Controlify](https://github.com/isXander/Controlify) and
  [Controlify-Enhanced](https://github.com/dx-Arcus/Controlify-Enhanced).
- Performance and configuration mods: [Lithium](https://modrinth.com/mod/lithium),
  [FerriteCore](https://modrinth.com/mod/ferritecore),
  [ImmediatelyFast](https://modrinth.com/mod/immediatelyfast),
  [YetAnotherConfigLib](https://modrinth.com/mod/yacl). Mod artifacts are
  resolved through the [Modrinth API](https://docs.modrinth.com/api/) and
  Fabric's official metadata/Maven services.
- Minecraft Java Edition and its official launcher metadata: [Minecraft](https://www.minecraft.net/).
  The game client and game assets are proprietary and are not included in the
  source release.
- Microsoft sign-in: Microsoft's documented
  [device authorization flow](https://learn.microsoft.com/entra/identity-platform/v2-oauth2-device-code).
- Java libraries/runtime: [Gson](https://github.com/google/gson) and
  [Eclipse Temurin](https://adoptium.net/); see the runtime's included notices.
- Black Shield server integration: [Paper](https://github.com/PaperMC/Paper),
  [ViaVersion](https://github.com/ViaVersion/ViaVersion),
  [.NET / ASP.NET Core](https://dotnet.microsoft.com/), and
  [Konscious Argon2](https://www.nuget.org/packages/Konscious.Security.Cryptography.Argon2/1.3.1).
- PS5 package tooling: [LibProsperoPkg](https://github.com/SvenGDK/LibProsperoPKG).
- PS5 selection-audio conversion: [PS5 AT9 Converter](https://github.com/blackbearreloaded/ps5-at9-converter).
- Microsoft sign-in integration: original JavaCraft Account mod source under
  `src/com/xxado/javacraft/account/`.
- Special thanks to **Abdullah M Qahtani** for his help and support, and to
  every maintainer and PS5 homebrew developer whose open-source work helped
  make this project possible.

The optional patch in `patches/javacraft-personalization.patch` modifies
Prospero Win and remains under its upstream LGPL-2.1-or-later terms; it is not
covered by JavaCraft's MIT grant. The locally patched Controlify binary is
excluded from the clean source export. A release that redistributes it must
also provide the exact corresponding patched source and notices.

## Support JavaCraft

Support and maintenance will continue whether or not you can contribute
financially. If JavaCraft is useful to you, an optional financial contribution
helps me keep investing time in fixes, compatibility work, and new ideas. There
is no obligation to donate. To support development, visit
[PayPal.me/AAlqhtani581](https://paypal.me/AAlqhtani581). If you encounter a
problem, have an idea, or want to discuss the project, join the
[BS6 Discord](https://discord.com/BS6) for community support and updates.
