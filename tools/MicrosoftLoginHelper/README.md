# JavaCraft Microsoft Login Helper

This Windows helper signs in through Microsoft's device-code page and sends the Minecraft session to JavaCraft over the local network. It does not request or store the Microsoft password.

1. Open JavaCraft settings on the PS5 and enter the same Microsoft app client ID used by the helper.
2. Choose **Sign in using the PC helper**. Keep the pairing screen open.
3. Run `MicrosoftLoginHelper.exe` on a PC on the same local network.
4. Enter the PS5 IP address, the same app client ID, and the one-time pairing key shown in JavaCraft.
5. Complete sign-in and any email or Authenticator verification on Microsoft's page.
6. Return to JavaCraft; the account should appear as signed in for this launch.

The session is sent once over ports `43821` through `43830` (the helper tries the range to avoid local port conflicts), encrypted with the one-time pairing key, and held in memory only. The PC helper does not save tokens or account details. A Minecraft Java entitlement is required for Online play.
