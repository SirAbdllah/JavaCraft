using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace JavaCraftUploader;

internal sealed record FtpEntry(string Name, bool IsDirectory, long Size)
{
    public static FtpEntry? Parse(string line)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        var trimmed = line.TrimEnd('\r', '\n');
        if (trimmed.StartsWith('d') || trimmed.StartsWith('-') || trimmed.StartsWith('l'))
        {
            var fields = trimmed.Split(' ', 9, StringSplitOptions.RemoveEmptyEntries);
            if (fields.Length >= 9 && long.TryParse(fields[4], NumberStyles.Integer,
                    CultureInfo.InvariantCulture, out var size))
            {
                var name = fields[8];
                if (fields[0].StartsWith('l') && name.Contains(" -> ", StringComparison.Ordinal))
                    name = name[..name.IndexOf(" -> ", StringComparison.Ordinal)];
                return new(name, fields[0][0] == 'd', size);
            }
        }

        var dos = System.Text.RegularExpressions.Regex.Match(trimmed,
            @"^\s*\d{1,2}-\d{1,2}-\d{2,4}\s+\d{1,2}:\d{2}(?:AM|PM)?\s+(<DIR>|\d+)\s+(.+)$",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        if (dos.Success)
        {
            var isDirectory = dos.Groups[1].Value.Equals("<DIR>", StringComparison.OrdinalIgnoreCase);
            _ = long.TryParse(dos.Groups[1].Value, out var size);
            return new(dos.Groups[2].Value, isDirectory, size);
        }

        return null;
    }
}

internal sealed class Ps5FtpClient : IDisposable
{
    private readonly TcpClient _control = new();
    private StreamReader? _reader;
    private StreamWriter? _writer;
    private readonly string _host;
    private string _currentDirectory = "/";

    public Ps5FtpClient(string host, int port)
    {
        _host = host;
        _control.ReceiveTimeout = 20000;
        _control.SendTimeout = 20000;
        _control.Connect(host, port);
        var stream = _control.GetStream();
        stream.ReadTimeout = 20000;
        stream.WriteTimeout = 20000;
        _reader = new StreamReader(stream, new UTF8Encoding(false), false, 4096, true);
        _writer = new StreamWriter(stream, new UTF8Encoding(false), 4096, true) { NewLine = "\r\n", AutoFlush = true };
        Expect(ReadReply(), 220);
        var user = Command("USER anonymous");
        if (user.Code == 331) Expect(Command("PASS javacraft@local"), 230);
        else Expect(user, 230);
        var type = Command("TYPE I");
        if (type.Code != 200) throw Error(type);
    }

    public void TestConnection()
    {
        var reply = Command("PWD");
        if (reply.Code != 257) throw Error(reply);
    }

    public IReadOnlyList<FtpEntry> List(string path)
    {
        ChangeDirectory(path);
        using var data = OpenPassiveData();
        var reply = Command("LIST");
        if (reply.Code != 125 && reply.Code != 150) throw Error(reply);
        using var memory = new MemoryStream();
        data.GetStream().CopyTo(memory);
        data.Close();
        Expect(ReadReply(), 226);
        var text = Encoding.UTF8.GetString(memory.ToArray());
        return text.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries)
            .Select(FtpEntry.Parse).Where(x => x is not null).Cast<FtpEntry>()
            .Where(x => x.Name is not "." and not "..").ToList();
    }

    public long? GetSize(string path)
    {
        var (parent, name) = SplitPath(path);
        ChangeDirectory(parent);
        var reply = Command("SIZE " + SafeArgument(name));
        if (reply.Code == 213)
        {
            var match = System.Text.RegularExpressions.Regex.Match(reply.Message, @"^213\s+(\d+)");
            if (match.Success && ulong.TryParse(match.Groups[1].Value, NumberStyles.Integer,
                    CultureInfo.InvariantCulture, out var size))
                return size == ulong.MaxValue || size > long.MaxValue ? null : (long)size;
        }
        if (reply.Code == 550 || reply.Code == 450) return null;
        throw Error(reply);
    }

    public void EnsureDirectory(string path)
    {
        var normalized = NormalizeDirectory(path);
        if (normalized == _currentDirectory) return;
        if (string.IsNullOrWhiteSpace(path) || normalized == "/")
        {
            ChangeDirectory("/");
            return;
        }
        if (_currentDirectory != "/") ChangeDirectory("/");
        var walked = "";
        foreach (var part in normalized.Split('/', StringSplitOptions.RemoveEmptyEntries))
        {
            var cwd = Command("CWD " + SafeArgument(part));
            if (cwd.Code != 250)
            {
                var mkd = Command("MKD " + SafeArgument(part));
                if (mkd.Code != 257 && mkd.Code != 250 && mkd.Code != 226 && mkd.Code != 550) throw Error(mkd);
                cwd = Command("CWD " + SafeArgument(part));
                if (cwd.Code != 250) throw Error(cwd);
            }
            walked += "/" + part;
            _currentDirectory = walked;
        }
    }

    public void Store(string remotePath, Stream source, long totalBytes,
        Action<long>? onProgress = null)
    {
        var (parent, name) = SplitPath(remotePath);
        EnsureDirectory(parent);
        using var data = OpenPassiveData();
        var reply = Command("STOR " + SafeArgument(name));
        if (reply.Code != 125 && reply.Code != 150) throw Error(reply);
        var buffer = new byte[128 * 1024];
        long transferred = 0;
        int read;
        var stream = data.GetStream();
        while ((read = source.Read(buffer, 0, buffer.Length)) > 0)
        {
            stream.Write(buffer, 0, read);
            transferred += read;
            onProgress?.Invoke(read);
        }
        data.Close();
        Expect(ReadReply(), 226);
        if (totalBytes >= 0 && transferred != totalBytes)
            throw new IOException($"Upload size mismatch for {name}: sent {transferred}, expected {totalBytes} bytes.");
    }

    public void Retrieve(string remotePath, Stream destination, Action<long>? onProgress = null)
    {
        var (parent, name) = SplitPath(remotePath);
        ChangeDirectory(parent);
        using var data = OpenPassiveData();
        var reply = Command("RETR " + SafeArgument(name));
        if (reply.Code != 125 && reply.Code != 150) throw Error(reply);
        var buffer = new byte[128 * 1024];
        int read;
        var stream = data.GetStream();
        while ((read = stream.Read(buffer, 0, buffer.Length)) > 0)
        {
            destination.Write(buffer, 0, read);
            onProgress?.Invoke(read);
        }
        data.Close();
        Expect(ReadReply(), 226);
    }

    public string RetrieveText(string remotePath)
    {
        using var memory = new MemoryStream();
        Retrieve(remotePath, memory);
        return Encoding.UTF8.GetString(memory.ToArray());
    }

    public void StoreText(string remotePath, string text)
    {
        var bytes = new UTF8Encoding(false).GetBytes(text);
        using var memory = new MemoryStream(bytes);
        Store(remotePath, memory, bytes.Length);
    }

    public void DeleteFile(string path)
    {
        var (parent, name) = SplitPath(path);
        ChangeDirectory(parent);
        var reply = Command("DELE " + SafeArgument(name));
        // Some PS5 FTP servers use 226 for a completed DELE instead of the
        // RFC 250 response; both mean the file was successfully removed.
        if (reply.Code is not (250 or 226)) throw Error(reply);
    }

    public void RemoveDirectory(string path)
    {
        var (parent, name) = SplitPath(path);
        ChangeDirectory(parent);
        var reply = Command("RMD " + SafeArgument(name));
        if (reply.Code is not (250 or 226)) throw Error(reply);
    }

    public void Rename(string from, string to)
    {
        var (fromParent, fromName) = SplitPath(from);
        var (toParent, toName) = SplitPath(to);
        if (!fromParent.Equals(toParent, StringComparison.Ordinal))
            throw new InvalidOperationException("FTP rename must stay within one directory.");
        ChangeDirectory(fromParent);
        Expect(Command("RNFR " + SafeArgument(fromName)), 350);
        var reply = Command("RNTO " + SafeArgument(toName));
        // Some PS5 FTP servers report a successful rename with 226 rather
        // than the usual 250 completion response.
        if (reply.Code is not (250 or 226)) throw Error(reply);
    }

    public string? TryCommand(string command)
    {
        var reply = Command(command);
        return reply.Code is >= 200 and < 400 ? reply.Message : null;
    }

    private void ChangeDirectory(string path)
    {
        var normalized = NormalizeDirectory(path);
        if (normalized == _currentDirectory) return;
        var reply = Command("CWD " + SafeArgument(normalized));
        if (reply.Code != 250) throw Error(reply);
        _currentDirectory = normalized;
    }

    private static string NormalizeDirectory(string path)
    {
        var parts = path.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Any(p => p is "." or ".." || p.Contains('\r') || p.Contains('\n')))
            throw new ArgumentException("Invalid FTP directory path.");
        return parts.Length == 0 ? "/" : "/" + string.Join('/', parts);
    }

    private TcpClient OpenPassiveData()
    {
        var reply = Command("PASV");
        if (reply.Code != 227) throw Error(reply);
        var match = System.Text.RegularExpressions.Regex.Match(reply.Message,
            @"\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)");
        if (!match.Success) throw new IOException("The PS5 FTP service returned an invalid passive-mode address.");
        var port = int.Parse(match.Groups[5].Value, CultureInfo.InvariantCulture) * 256
                 + int.Parse(match.Groups[6].Value, CultureInfo.InvariantCulture);
        var client = new TcpClient(AddressFamily.InterNetwork) { ReceiveTimeout = 30000, SendTimeout = 30000 };
        // Some console FTP servers advertise 0.0.0.0 or a stale interface IP.
        // The control connection host is the reachable address on the same LAN.
        client.Connect(_host, port);
        return client;
    }

    private FtpReply Command(string command)
    {
        if (command.Contains('\r') || command.Contains('\n')) throw new ArgumentException("Invalid FTP command.");
        (_writer ?? throw new ObjectDisposedException(nameof(Ps5FtpClient))).WriteLine(command);
        return ReadReply();
    }

    private FtpReply ReadReply()
    {
        var reader = _reader ?? throw new ObjectDisposedException(nameof(Ps5FtpClient));
        var first = reader.ReadLine() ?? throw new IOException("The PS5 closed the FTP control connection.");
        if (first.Length < 3 || !int.TryParse(first[..3], out var code))
            throw new IOException("Unexpected FTP response: " + first);
        var lines = new List<string> { first };
        if (first.Length > 3 && first[3] == '-')
        {
            string? line;
            while ((line = reader.ReadLine()) is not null)
            {
                lines.Add(line);
                if (line.StartsWith(code.ToString(CultureInfo.InvariantCulture) + " ", StringComparison.Ordinal)) break;
            }
        }
        return new FtpReply(code, string.Join("\n", lines));
    }

    private static void Expect(FtpReply reply, int code)
    {
        if (reply.Code != code) throw Error(reply);
    }

    private static Exception Error(FtpReply reply) => new IOException("PS5 FTP: " + reply.Message);

    private static string SafeArgument(string value)
    {
        if (value.Contains('\r') || value.Contains('\n') || value.Contains('\0'))
            throw new ArgumentException("Invalid remote path.");
        return value;
    }

    internal static (string Parent, string Name) SplitPath(string path)
    {
        var full = path.Replace('\\', '/').TrimEnd('/');
        var index = full.LastIndexOf('/');
        if (index < 0) return ("/", full);
        return (index == 0 ? "/" : full[..index], full[(index + 1)..]);
    }

    public void Dispose()
    {
        try { _writer?.WriteLine("QUIT"); } catch { }
        _reader?.Dispose();
        _writer?.Dispose();
        _control.Dispose();
    }

    private sealed record FtpReply(int Code, string Message);
}
