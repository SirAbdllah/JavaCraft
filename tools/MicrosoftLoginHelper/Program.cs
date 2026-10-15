using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Net.Sockets;

internal static class Program
{
    private const string Aad = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private const int PairingPortFirst = 43821;
    private const int PairingPortLast = 43830;
    private static readonly byte[] PairingAad = Encoding.UTF8.GetBytes("JavaCraftSessionV1");
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(90) };
    private static readonly JsonSerializerOptions JsonOptions = new();

    private static async Task<int> Main()
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.WriteLine("JavaCraft Microsoft Login Helper");
        Console.WriteLine("Sign-in happens on Microsoft's official page. This tool does not ask for or save your password.\n");

        string host = ReadRequired("PS5 IP", "192.168.8.33");
        if (!IPAddress.TryParse(host, out var address) || address.AddressFamily != AddressFamily.InterNetwork)
            return Fail("Enter the PS5's IPv4 address, such as 192.168.8.33.");
        string clientId = ReadClientId();
        if (!Guid.TryParse(clientId, out _))
            return Fail("This helper has no valid JavaCraft Microsoft app configuration. Rebuild it with the app ID file beside it.");
        string pairingCode = ReadRequired("One-time pairing key shown in JavaCraft", null).ToLowerInvariant();
        if (pairingCode.Length != 32 || !pairingCode.All(Uri.IsHexDigit))
            return Fail("The one-time pairing key must contain 32 hexadecimal characters.");

        try
        {
            var account = await SignInAsync(clientId);
            await SendSessionAsync(host, pairingCode, clientId, account);
            Console.WriteLine($"\nMicrosoft account {account.Name} connected to JavaCraft. Close this window when ready.");
            return 0;
        }
        catch (OperationCanceledException)
        {
            return Fail("Sign-in was cancelled.");
        }
        catch (Exception ex)
        {
            return Fail(ex.Message);
        }
    }

    private static async Task<MinecraftAccount> SignInAsync(string clientId)
    {
        using var start = await Http.PostAsync($"{Aad}/devicecode",
            new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = clientId,
                ["scope"] = "XboxLive.signin offline_access"
            }));
        using var device = await ReadJsonAsync(start);
        string deviceCode = Required(device.RootElement, "device_code");
        string userCode = Required(device.RootElement, "user_code");
        string verificationUri = Optional(device.RootElement, "verification_uri") ?? "https://microsoft.com/devicelogin";
        int interval = Math.Max(1, OptionalInt(device.RootElement, "interval") ?? 5);
        int seconds = OptionalInt(device.RootElement, "expires_in") ?? 900;

        Console.WriteLine("\nMicrosoft sign-in required:");
        Console.WriteLine($"  Open: {verificationUri}");
        Console.WriteLine($"  Code: {userCode}");
        Console.WriteLine("Complete any password, email-code, or Authenticator checks on Microsoft's page.");
        try { Process.Start(new ProcessStartInfo(verificationUri) { UseShellExecute = true }); }
        catch { }

        DateTimeOffset deadline = DateTimeOffset.UtcNow.AddSeconds(seconds);
        JsonDocument? token = null;
        while (DateTimeOffset.UtcNow < deadline)
        {
            await Task.Delay(TimeSpan.FromSeconds(interval));
            using var response = await Http.PostAsync($"{Aad}/token",
                new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    ["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code",
                    ["client_id"] = clientId,
                    ["device_code"] = deviceCode
                }));
            string body = await response.Content.ReadAsStringAsync();
            using var json = JsonDocument.Parse(body);
            if (response.IsSuccessStatusCode)
            {
                token = JsonDocument.Parse(body);
                break;
            }
            string error = Optional(json.RootElement, "error") ?? "request_failed";
            if (error == "authorization_pending") continue;
            if (error == "slow_down") { interval += 5; continue; }
            if (error == "authorization_declined") throw new InvalidOperationException("Microsoft sign-in was declined.");
            if (error == "expired_token") throw new InvalidOperationException("The Microsoft sign-in code expired. Run the helper again.");
            throw new InvalidOperationException(ServiceError("Microsoft sign-in", json.RootElement, response.StatusCode));
        }
        if (token is null) throw new TimeoutException("Microsoft sign-in timed out. Run the helper again.");
        using (token)
        {
            string msaToken = Required(token.RootElement, "access_token");
            using var xbl = await PostJsonAsync("https://user.auth.xboxlive.com/user/authenticate", new
            {
                Properties = new { AuthMethod = "RPS", SiteName = "user.auth.xboxlive.com", RpsTicket = "d=" + msaToken },
                RelyingParty = "http://auth.xboxlive.com",
                TokenType = "JWT"
            });
            string xboxToken = Required(xbl.RootElement, "Token");
            using var xsts = await PostJsonAsync("https://xsts.auth.xboxlive.com/xsts/authorize", new
            {
                Properties = new { SandboxId = "RETAIL", UserTokens = new[] { xboxToken } },
                RelyingParty = "rp://api.minecraftservices.com/",
                TokenType = "JWT"
            });
            string xstsToken = Required(xsts.RootElement, "Token");
            JsonElement xui = xsts.RootElement.GetProperty("DisplayClaims").GetProperty("xui")[0];
            string uhs = Required(xui, "uhs");
            string xuid = Optional(xui, "xid") ?? "0";
            using var minecraft = await PostJsonAsync("https://api.minecraftservices.com/authentication/login_with_xbox",
                new { identityToken = $"XBL3.0 x={uhs};{xstsToken}" });
            string minecraftToken = Required(minecraft.RootElement, "access_token");

            using var entitlements = await GetJsonAsync("https://api.minecraftservices.com/entitlements/mcstore", minecraftToken);
            if (!entitlements.RootElement.TryGetProperty("items", out var items) || items.GetArrayLength() == 0)
                throw new InvalidOperationException("This Microsoft account does not have Minecraft Java Edition entitlement.");
            using var profile = await GetJsonAsync("https://api.minecraftservices.com/minecraft/profile", minecraftToken);
            return new MinecraftAccount(Required(profile.RootElement, "name"),
                Required(profile.RootElement, "id"), minecraftToken, xuid);
        }
    }

    private static async Task SendSessionAsync(string host, string pairingCode, string clientId, MinecraftAccount account)
    {
        byte[] key = SHA256.HashData(Encoding.UTF8.GetBytes(pairingCode));
        byte[] nonce = RandomNumberGenerator.GetBytes(12);
        byte[] plaintext = JsonSerializer.SerializeToUtf8Bytes(new
        {
            name = account.Name,
            uuid = account.Uuid,
            accessToken = account.AccessToken,
            xuid = account.Xuid,
            userType = "msa",
            clientId
        }, JsonOptions);
        byte[] ciphertext = new byte[plaintext.Length];
        byte[] tag = new byte[16];
        using (var aes = new AesGcm(key, tag.Length)) aes.Encrypt(nonce, plaintext, ciphertext, tag, PairingAad);

        byte[] payload = new byte[nonce.Length + tag.Length + ciphertext.Length];
        Buffer.BlockCopy(nonce, 0, payload, 0, nonce.Length);
        Buffer.BlockCopy(tag, 0, payload, nonce.Length, tag.Length);
        Buffer.BlockCopy(ciphertext, 0, payload, nonce.Length + tag.Length, ciphertext.Length);
        string encodedPayload = Convert.ToBase64String(payload);
        bool sent = false;
        bool foundListener = false;
        try
        {
            for (int port = PairingPortFirst; port <= PairingPortLast; port++)
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                using var content = new StringContent(encodedPayload, Encoding.ASCII, "text/plain");
                try
                {
                    using var response = await Http.PostAsync($"http://{host}:{port}/session", content, timeout.Token);
                    foundListener = true;
                    if (response.IsSuccessStatusCode)
                    {
                        sent = true;
                        break;
                    }
                }
                catch (HttpRequestException) { }
                catch (OperationCanceledException) { }
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(plaintext);
            CryptographicOperations.ZeroMemory(ciphertext);
            CryptographicOperations.ZeroMemory(payload);
        }
        if (!sent)
        {
            if (foundListener)
                throw new InvalidOperationException("JavaCraft rejected the one-time pairing. Start a new pairing and check that the IP and key match.");
            throw new IOException($"Could not reach JavaCraft on ports {PairingPortFirst}-{PairingPortLast}. Check that the PS5 pairing screen is open and both devices are on the same local network.");
        }
    }

    private static async Task<JsonDocument> PostJsonAsync<T>(string url, T body)
    {
        using var response = await Http.PostAsJsonAsync(url, body, JsonOptions);
        return await ReadJsonAsync(response);
    }

    private static async Task<JsonDocument> GetJsonAsync(string url, string bearer)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", bearer);
        using var response = await Http.SendAsync(request);
        return await ReadJsonAsync(response);
    }

    private static async Task<JsonDocument> ReadJsonAsync(HttpResponseMessage response)
    {
        string body = await response.Content.ReadAsStringAsync();
        JsonDocument json;
        try { json = JsonDocument.Parse(body); }
        catch { throw new InvalidOperationException($"A sign-in service returned an unreadable response (HTTP {(int)response.StatusCode})."); }
        if (!response.IsSuccessStatusCode)
        {
            string message = ServiceError("Sign-in service", json.RootElement, response.StatusCode);
            json.Dispose();
            throw new InvalidOperationException(message);
        }
        return json;
    }

    private static string ServiceError(string name, JsonElement body, HttpStatusCode status)
    {
        string? code = Optional(body, "error");
        string? description = Optional(body, "error_description") ?? Optional(body, "Message");
        if (code == "unauthorized_client")
            return "Microsoft rejected this app ID. It must allow personal accounts and public device-code sign-in.";
        return $"{name} rejected the request ({code ?? $"HTTP {(int)status}"}): {description ?? "request failed"}";
    }

    private static string Required(JsonElement element, string name) =>
        Optional(element, name) ?? throw new InvalidOperationException($"The sign-in response is missing {name}.");

    private static string? Optional(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static int? OptionalInt(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.TryGetInt32(out int parsed) ? parsed : null;

    private static string ReadRequired(string label, string? defaultValue)
    {
        Console.Write(defaultValue is null ? $"{label}: " : $"{label} [{defaultValue}]: ");
        string? answer = Console.ReadLine()?.Trim();
        return string.IsNullOrEmpty(answer) && defaultValue is not null ? defaultValue : answer ?? "";
    }

    private static string ReadClientId()
    {
        string fromEnvironment = Environment.GetEnvironmentVariable("JAVACRAFT_MS_CLIENT_ID")?.Trim() ?? "";
        if (Guid.TryParse(fromEnvironment, out _)) return fromEnvironment;
        string file = Path.Combine(AppContext.BaseDirectory, "microsoft-client-id.txt");
        if (!File.Exists(file)) return "";
        string fromFile = File.ReadAllText(file).Trim();
        return Guid.TryParse(fromFile, out _) ? fromFile : "";
    }

    private static int Fail(string message)
    {
        Console.Error.WriteLine($"\n{message}");
        return 1;
    }

    private sealed record MinecraftAccount(string Name, string Uuid, string AccessToken, string Xuid);
}
