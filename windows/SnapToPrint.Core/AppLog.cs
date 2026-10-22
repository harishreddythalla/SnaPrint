namespace SnapToPrint.Core;

/// <summary>File log + ring buffer of recent errors. Never log tokens or photo contents.</summary>
public sealed class AppLog
{
    private readonly string? _file;
    private readonly object _lock = new();
    private readonly List<string> _errors = new();

    public AppLog(string? dir)
    {
        if (dir == null) return;
        Directory.CreateDirectory(dir);
        _file = Path.Combine(dir, "snaptoprint.log");
    }

    public string? Directory_ => _file == null ? null : Path.GetDirectoryName(_file);
    public void Info(string m) => Write("INFO", m);

    public void Error(string m)
    {
        Write("ERROR", m);
        lock (_lock)
        {
            _errors.Insert(0, $"{DateTime.Now:HH:mm:ss}  {m}");
            if (_errors.Count > 50) _errors.RemoveAt(50);
        }
    }

    public string[] RecentErrors() { lock (_lock) return _errors.ToArray(); }

    private void Write(string level, string m)
    {
        if (_file == null) return;
        try
        {
            lock (_lock)
            {
                if (File.Exists(_file) && new FileInfo(_file).Length > 5_000_000) File.Move(_file, _file + ".old", true);
                File.AppendAllText(_file, $"{DateTime.Now:O} {level} {m}{Environment.NewLine}");
            }
        }
        catch { /* logging must never crash the app */ }
    }
}
