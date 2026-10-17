# JavaCraft PS5

[![Donate](https://img.shields.io/badge/Donate-PayPal-0070ba)](https://paypal.me/AAlqhtani581)
[![Discord](https://img.shields.io/badge/Community-BS6-5865f2)](https://discord.com/invite/BS6)

Minecraft Java Edition on PS5 through **JavaCraft-Win**, based on Wine and Prospero Win.
Created by **V0BIT**. Current release: **1.2.2**.

## Features

- Minecraft 26.1.1, 26.1.2, 26.2 and 26.3, with separate game instances.
- DualSense controls, PS5 button icons, keyboard and mouse support.
- Singleplayer and multiplayer, with G1 garbage collection and the ZIP storage bridge.
- Prism account pairing, saved sessions on PC/PS5 and the official player name.
- Windows uploader: install, repair, remove, accounts, mods, worlds and resource/data packs.
- Linux uploader: upload, repair and delete.
- Verified mod updates and optional Black Shield Community integration.

## Install or update

1. Enable FTP on your PS5 and open the uploader.
2. Enter the PS5 IP address and FTP port, then connect.
3. Select the prepared `dist/PS5/data` package and choose Install or Repair.
4. Open JavaCraft on PS5 and select the game version and instance.

Linux: run `sh start.sh` in the Linux uploader folder. Requires Python 3.11+ and Tkinter.

## Connect your account

1. Sign in to Minecraft Java through Prism Launcher on your PC.
2. On PS5, open **JavaCraft Account → Pair account**.
3. In the Windows uploader, open **Accounts**, refresh, and select your account.
4. Enter the PS5 port and six-digit code, then connect.

Use **Open Prism** in the Windows app to open Prism or get its download link.
Enable Remember on PC/PS5 to keep the session. Signed-in accounts use their official name.

## Changes

See [CHANGELOG.md](CHANGELOG.md) for the complete list of fixes and additions.
Build instructions: [BUILD.md](BUILD.md). Component notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Credits

[Prospero Win](https://github.com/mpereiraesaa/prospero-win), [Wine](https://gitlab.winehq.org/wine/wine),
[Controlify](https://github.com/isXander/Controlify), [Fabric](https://fabricmc.net/),
BlackBearReloaded, mihawk-99 and the upstream mod authors.
Special thanks to **Abdullah M Qahtani**.

Original JavaCraft code: [MIT](LICENSE). Dependencies retain their own licenses.
Minecraft, game assets, PS5 runtime binaries and account data are not included in the source release.
