using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;

internal static class Program
{
    // Microsoft Graph PowerShell's first-party public client. Authentication is
    // delegated through Microsoft's device-code page; no password is collected.
    private const string GraphPowerShellClientId = "14d82eec-204b-4c2f-b7e8-296a70dab67e";
    private const string Authority = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    private const string GraphScope = "https://graph.microsoft.com/Application.ReadWrite.All offline_access openid profile";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(45) };
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    private static async Task<int> Main()
    {
        Console.OutputEncoding = Encoding.UTF8;
        Console.WriteLine("JavaCraft Client ID Helper");
        Console.WriteLine("Creates a Microsoft Entra public app for personal Microsoft accounts.");
        Console.WriteLine("Sign in only on Microsoft's page. This tool never asks for your password or saves tokens.\n");

        try
        {
            Console.Write("App name [JavaCraft PS5]: ");
            string appName = Console.ReadLine()?.Trim() ?? "";
            if (appName.Length == 0) appName = "JavaCraft PS5";

            using JsonDocument device = await StartDeviceLoginAsync();
            JsonElement data = device.RootElement;
            string userCode = Required(data, "user_code");
            string verificationUri = Optional(data, "verification_uri") ?? "https://microsoft.com/devicelogin";
            string message = Optional(data, "message") ?? $"Open {verificationUri} and enter code {userCode}.";
            int interval = Math.Max(1, OptionalInt(data, "interval") ?? 5);
            int expiresIn = OptionalInt(data, "expires_in") ?? 900;
            string deviceCode = Required(data, "device_code");

            Console.WriteLine("\n" + message);
            Console.WriteLine("Waiting for Microsoft sign-in...");
            try
            {
                Process.Start(new ProcessStartInfo(verificationUri) { UseShellExecute = true });
            }
            catch { /* The URL and code remain visible for manual use. */ }

            string accessToken = await PollForTokenAsync(deviceCode, interval, expiresIn);
            string clientId = await CreateJavaCraftRegistrationAsync(appName, accessToken);
            string outputPath = Path.Combine(AppContext.BaseDirectory, "JavaCraft-Microsoft-Client-ID.txt");
            await File.WriteAllTextAsync(outputPath, clientId + Environment.NewLine, new UTF8Encoding(false));

            Console.WriteLine("\nApp registration created.");
            Console.WriteLine("Application (client) ID: " + clientId);
            Console.WriteLine("Saved to: " + outputPath);
            Console.WriteLine("Paste this ID into JavaCraft's Microsoft Client ID field.");
            Console.WriteLine("Press Enter to close.");
            Console.ReadLine();
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine("\nCould not create the app registration:");
            Console.Error.WriteLine(error.Message);
            Console.Error.WriteLine("If Microsoft reports that the account has no directory or is not allowed to register apps, create or select a Microsoft Entra tenant first, then rerun this tool.");
            Console.WriteLine("Press Enter to close.");
            Console.ReadLine();
            return 1;
        }
    }

    private static async Task<JsonDocument> StartDeviceLoginAsync()
    {
        using var response = await Http.PostAsync($"{Authority}/devicecode", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["client_id"] = GraphPowerShellClientId,
            ["scope"] = GraphScope
        }));
        JsonDocument body = await ReadJsonAsync(response);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(ErrorDescription(body.RootElement, response.StatusCode));
        return body;
    }

    private static async Task<string> PollForTokenAsync(string deviceCode, int interval, int expiresIn)
    {
        DateTimeOffset expires = DateTimeOffset.UtcNow.AddSeconds(expiresIn);
        while (DateTimeOffset.UtcNow < expires)
        {
            await Task.Delay(TimeSpan.FromSeconds(interval));
            using var response = await Http.PostAsync($"{Authority}/token", new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "urn:ietf:params:oauth:grant-type:device_code",
                ["client_id"] = GraphPowerShellClientId,
                ["device_code"] = deviceCode
            }));
            using JsonDocument body = await ReadJsonAsync(response);
            if (response.IsSuccessStatusCode)
                return Required(body.RootElement, "access_token");

            string error = Optional(body.RootElement, "error") ?? "unknown_error";
            if (error == "authorization_pending") continue;
            if (error == "slow_down") { interval += 5; continue; }
            throw new InvalidOperationException(ErrorDescription(body.RootElement, response.StatusCode));
        }
        throw new TimeoutException("Microsoft's device sign-in code expired. Run the helper again for a new code.");
    }

    private static async Task<string> CreateJavaCraftRegistrationAsync(string appName, string accessToken)
    {
        var payload = new
        {
            displayName = appName,
            signInAudience = "AzureADandPersonalMicrosoftAccount",
            isFallbackPublicClient = true
        };
        using var request = new HttpRequestMessage(HttpMethod.Post, "https://graph.microsoft.com/v1.0/applications")
        {
            Content = JsonContent.Create(payload, options: JsonOptions)
        };
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);
        using var response = await Http.SendAsync(request);
        using JsonDocument body = await ReadJsonAsync(response);
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException(ErrorDescription(body.RootElement, response.StatusCode));
        return Required(body.RootElement, "appId");
    }

    private static async Task<JsonDocument> ReadJsonAsync(HttpResponseMessage response)
    {
        await using Stream stream = await response.Content.ReadAsStreamAsync();
        return await JsonDocument.ParseAsync(stream);
    }

    private static string ErrorDescription(JsonElement root, HttpStatusCode status)
    {
        if (root.TryGetProperty("error_description", out JsonElement description))
            return description.GetString() ?? $"Microsoft returned HTTP {(int)status}.";
        if (root.TryGetProperty("error", out JsonElement error) && error.ValueKind == JsonValueKind.Object)
        {
            string code = Optional(error, "code") ?? "Microsoft Graph error";
            string message = Optional(error, "message") ?? $"HTTP {(int)status}.";
            return $"{code}: {message}";
        }
        return $"Microsoft returned HTTP {(int)status}.";
    }

    private static string Required(JsonElement value, string property) =>
        Optional(value, property) ?? throw new InvalidOperationException($"Microsoft's response did not contain '{property}'.");

    private static string? Optional(JsonElement value, string property) =>
        value.TryGetProperty(property, out JsonElement item) && item.ValueKind == JsonValueKind.String ? item.GetString() : null;

    private static int? OptionalInt(JsonElement value, string property) =>
        value.TryGetProperty(property, out JsonElement item) && item.TryGetInt32(out int result) ? result : null;
}
