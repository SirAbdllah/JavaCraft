# JavaCraft Client ID Helper

This Windows helper uses Microsoft's device-code sign-in, then creates a public Microsoft Entra app registration configured to accept personal Microsoft accounts. It displays the Application (client) ID and writes it to `JavaCraft-Microsoft-Client-ID.txt` beside the executable.

The helper does not request or store the user's password, access token, or refresh token. It asks Microsoft Graph for `Application.ReadWrite.All` because Microsoft requires that permission to create an app registration. The signed-in account must be allowed to create app registrations in its directory.

Creating a client ID does not guarantee that Minecraft Services will accept it. If sign-in succeeds through Xbox but Minecraft Services returns `Invalid app registration`, the registration needs Microsoft's authorization for that service; the Entra app settings alone cannot grant it. See Microsoft's [AppRegInfo](https://aka.ms/AppRegInfo) information page.

Build with .NET 8:

```powershell
dotnet publish .\MicrosoftClientIdHelper.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o ..\..\dist\JavaCraftClientIdHelper
```
