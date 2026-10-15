# Black Shield Community

BS6 is a small server gate for the JavaCraft PS5 client. Players do not create a BS6 account, enter a password, request a ticket, or run a separate API. The title-screen **Play BS6 Server** button connects directly to `appt-engine.online:25565` by default. The same host serves the JavaCraft mod update feed over HTTPS.

## What the gate checks

- The Paper/Purpur plugin advertises the `blackshield:hello` channel.
- The Fabric mod responds after joining with its platform marker.
- The plugin keeps a `ps5` response and kicks clients that report `pc`, send an invalid response, or do not have the mod.
- Accepted players receive the `[PS]` name prefix.

The mod identifies the standard JavaCraft PS5 install by checking for `javacraft.home=C:\\JavaCraftPS5` and a Java runtime under `C:\\JavaCraftPS5\\runtime`. It always connects to `appt-engine.online:25565`; there is no per-player IP setting.

For Minecraft traffic, configure `appt-engine.online` (or a dedicated play subdomain) as DNS-only to the game server unless Cloudflare Spectrum or another TCP proxy is configured. A normal Cloudflare HTTP proxy does not forward the Minecraft Java port `25565`.

## Install the server plugin

1. Copy `paper-plugin/build/libs/black-shield-community-paper-1.0.0.jar` into the server's `plugins` folder.
2. Start the Paper/Purpur 26.3 server once so it creates `plugins/BlackShieldCommunity/config.yml`.
3. Restart the server. The default handshake timeout is 3 seconds and can be changed with `handshake-timeout-ticks` (20 ticks = 1 second).
4. Install the matching Black Shield Community Fabric mod on the JavaCraft client. The mod is built into each supported JavaCraft modpack (26.1.1, 26.1.2, 26.2 and 26.3).

The Paper plugin gate does not require an account API, server secret, or Microsoft credential handling. Keep Minecraft's normal server authentication enabled. Use ViaVersion if the 26.3 server must accept older 26.1/26.2 clients.

## Important platform limit

This is a practical filter for ordinary clients: a normal PC install reports `pc`, and clients without the mod do not answer. Minecraft/Fabric clients cannot provide trusted proof of the physical device they run on. A modified PC client can copy the mod or forge its marker, so this plugin cannot guarantee hardware-level PS5-only access. For strict exclusion of PC players, use a private allowlist or network access control in addition to the mod check.

## Build

Run `build-all.ps1` from this directory. It builds the Paper plugin and Fabric mod jars for the staged JavaCraft versions. The client source is in `fabric-mod/`; the server source is in `paper-plugin/`.

## Credits and licensing

Project source and third-party notices are in `LICENSE` and `THIRD_PARTY_NOTICES.md`. Fabric API, Fabric Loader, Paper API and ViaVersion retain their own licenses.
