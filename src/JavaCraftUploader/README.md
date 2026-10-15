# JavaCraft PS5 Uploader

JavaCraft PS5 Uploader is a Windows desktop utility for transferring an existing
JavaCraft PS5 app-folder package to a PS5 over its local FTP service. It is a
file uploader and manager; it does not jailbreak a console, install or create an
fPKG, or download Minecraft from Mojang.

## Three main actions

- **Manage Mods & Worlds** — upload/remove user `.jar` mods, create ZIP backups
  of worlds, or delete a selected world. Files whose names identify JavaCraft,
  Controlify JavaCraft, or Black Shield Community are protected from removal.
- **Install JavaCraft** — upload every file from the complete local `PS5\data`
  package, including the PS5 app/emulator, the Minecraft prefix, Java runtime,
  assets, libraries, natives, versions, and bundled mods. Files at matching
  console paths are replaced; files not present in the package are left alone.
  The installer also registers the JavaCraft profile and its OnionHEN or
  etaHEN app-jailbreak title entry when that configuration is present.
- **Uninstall from PS5** — fully remove the JavaCraft app and bundled emulator,
  the Minecraft-only prefix (all worlds, mods, versions, runtime, account data,
  and settings), the JavaCraft profile, and its OnionHEN / etaHEN allowlist
  entries. Shared Prospero Win files and profiles used by other apps are
  preserved. The app asks for confirmation twice because worlds and account
  data are deleted.

## Requirements

- Windows 10/11 x64.
- PS5 and PC on the same trusted local network.
- FTP homebrew service active on the PS5 (default port `2121`).
- The complete local JavaCraft package folder containing:
  - `homebrew\PPSA99995\eboot.bin`
  - `homebrew\PPSA99995\win\` (the bundled PS5 emulator runtime)
  - `prospero-win\profiles\minecraft-java.profile`
  - an initialized Wine prefix with `system.reg`, `user.reg`, `userdef.reg`,
    `drive_c\windows\system32`, `syswow64`, `winsxs`, and
    `dosdevices\.pw-symlinks`
  - `prospero-win\prefixes\minecraft-java\drive_c\JavaCraftPS5\runtime\java-runtime-epsilon\bin\javaw.exe`
  - The Minecraft `assets`, `libraries`, `versions`, `natives`, and `game\mods` folders.

The generated package currently lives at `dist\PS5\data` in the JavaCraft
project. Select that `data` folder in the app. The uploader checks the Wine
prefix before installation so an incomplete package cannot produce a launcher
that immediately crashes. Minecraft game files and user data are not included
in the uploader release and must not be redistributed without the rights to do
so.

## Use

1. Start the PS5 FTP service, enter its private IP and FTP port, then choose **Connect**.
2. The app checks whether JavaCraft is installed. Choose **Install JavaCraft** if missing, or **Repair JavaCraft** if files are missing or the game needs repair.
3. The app prepares the package and transfers files in the background. Worlds, player options, account data, and logs are preserved. Keep the PC and PS5 awake until complete.
4. Use **Manage Mods & Worlds** for user mods and world backups. JavaCraft, Controlify JavaCraft, and Black Shield Community are locked.

The package is part of the JavaCraft project at `dist\PS5\data`. When running the uploader from the project's `dist` folder, it finds this package automatically. Select that `data` folder manually if the project is elsewhere.

The uploader is credited to **v0bit**. BS6 Discord: <https://discord.com/BS6>.

The app only accepts private IPv4 addresses. FTP is not encrypted, so do not
forward the console FTP port to the internet or use the tool over an untrusted
network. The app does not collect Microsoft passwords, tokens, or account
sessions.

## HEN compatibility

The JavaCraft app uses the shared `/download0/etahen_jailbreak` request and
the Prospero Win elevation fallback. The uploader adds `PPSA99995` to an
existing OnionHEN or etaHEN `config.ini`. With etaHEN, enable app-jailbreak for
the title in its settings/Toolbox when required, then reload etaHEN. Other HENs
need a compatible app-jailbreak request handler and title allowlist. This does
not add a firmware exploit or make an HEN build support a firmware it does not
already support; firmware 13.60 depends on the exact HEN build installed.

## Build from source

```powershell
dotnet build tools\JavaCraftUploader\JavaCraftUploader.csproj -c Release
```

To publish a portable Windows x64 executable:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-uploader.ps1
```

The original uploader source is part of the JavaCraft project license. The
JavaCraft app, Prospero Win runtime, Minecraft, mods, and assets have their own
licenses and distribution terms; see the project notices before publishing
those files.
