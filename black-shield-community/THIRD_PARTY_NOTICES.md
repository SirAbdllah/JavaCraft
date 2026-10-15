# Third-party notices

Black Shield Community contains original project code under the MIT License in `LICENSE`. It uses or integrates with these projects, which keep their own licenses:

- [Minecraft Java Edition](https://www.minecraft.net/), Mojang/Microsoft — proprietary game files are not included in this source project.
- [Fabric Loader](https://github.com/FabricMC/fabric-loader) and [Fabric API](https://github.com/FabricMC/fabric-api) — install the matching Fabric API separately in the JavaCraft game profile.
- [Paper](https://github.com/PaperMC/Paper) / Paper API — compile-time server API; the Paper server itself is not bundled.
- [ViaVersion](https://github.com/ViaVersion/ViaVersion) — separately installed server plugin for protocol translation; it is not bundled.
- [Konscious.Security.Cryptography.Argon2](https://www.nuget.org/packages/Konscious.Security.Cryptography.Argon2/1.3.1) 1.3.1 — MIT; used by the API for Argon2id password hashing. The package is restored by NuGet and is not copied into the source archive.
- [.NET / ASP.NET Core](https://dotnet.microsoft.com/) — API framework; install the supported .NET runtime on the API host or publish it with the desired deployment options.

Do not redistribute Minecraft assets or Sony/PS5 system software with this project. See each upstream project for its current license text and notices.
