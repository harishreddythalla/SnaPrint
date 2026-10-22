using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace SnapToPrint.Core;

public sealed class ConfigData
{
    public string DestRoot { get; set; } = "";
    public string TokenProtected { get; set; } = "";
    public string ManualIp { get; set; } = "";
    public string LastHost { get; set; } = "";
}

/// <summary>Settings in %APPDATA%\SnapToPrint\config.json. The pairing token is protected with Windows DPAPI.</summary>
public sealed class ConfigStore
{
    private readonly string _path;
    private readonly object _l = new();
    private ConfigData _d;

    public ConfigStore(string dir)
    {
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "config.json");
        _d = Load();
        if (string.IsNullOrEmpty(_d.DestRoot))
            _d.DestRoot = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "SnapToPrint");
    }

    public string DestRoot { get { lock (_l) return _d.DestRoot; } }
    public string ManualIp { get { lock (_l) return _d.ManualIp; } }
    public string LastHost { get { lock (_l) return _d.LastHost; } }

    public string Token
    {
        get
        {
            lock (_l)
            {
                if (string.IsNullOrEmpty(_d.TokenProtected)) return "";
                try { return Unprotect(_d.TokenProtected); } catch { return ""; }
            }
        }
    }

    public static string Normalize(string s) => new string(s.Where(char.IsLetterOrDigit).ToArray()).ToUpperInvariant();

    public void Save(string destRoot, string tokenInput, string manualIp)
    {
        lock (_l)
        {
            if (!string.IsNullOrWhiteSpace(destRoot)) _d.DestRoot = destRoot.Trim();
            var t = Normalize(tokenInput);
            if (t.Length > 0) _d.TokenProtected = Protect(t);
            _d.ManualIp = manualIp.Trim();
            Persist();
        }
    }

    public void SetLastHost(string host)
    {
        lock (_l) { _d.LastHost = host; Persist(); }
    }

    private ConfigData Load()
    {
        try { return File.Exists(_path) ? JsonSerializer.Deserialize<ConfigData>(File.ReadAllText(_path)) ?? new() : new(); }
        catch { return new(); }
    }

    private void Persist()
    {
        var tmp = _path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(_d));
        File.Move(tmp, _path, true);
    }

    private static string Protect(string s)
    {
        var b = Encoding.UTF8.GetBytes(s);
        if (OperatingSystem.IsWindows()) b = ProtectedData.Protect(b, null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(b);
    }

    private static string Unprotect(string s)
    {
        var b = Convert.FromBase64String(s);
        if (OperatingSystem.IsWindows()) b = ProtectedData.Unprotect(b, null, DataProtectionScope.CurrentUser);
        return Encoding.UTF8.GetString(b);
    }
}
