namespace SnapToPrint.Core;

public static class FolderNaming
{
    public static string Sanitize(string? label)
    {
        if (string.IsNullOrWhiteSpace(label)) return "";
        var invalid = Path.GetInvalidFileNameChars();
        var chars = label.Trim().Select(c => invalid.Contains(c) || c == ' ' ? '_' : c).ToArray();
        var s = new string(chars).Trim('_', '.');
        return s.Length > 40 ? s[..40] : s;
    }

    public static string EventFolderName(long startMs, string? label)
    {
        var dt = DateTimeOffset.FromUnixTimeMilliseconds(startMs).ToLocalTime();
        var safe = Sanitize(label);
        return $"Event_{dt:yyyy-MM-dd_HHmmss}" + (safe.Length > 0 ? "_" + safe : "");
    }

    /// <summary>Deterministic per photo (seq + capture time), so a retry maps to the same name.</summary>
    public static string FileName(PhotoItem i, string? suffix = null)
    {
        var ext = Path.GetExtension(i.FileName ?? "");
        ext = new string(ext.Where(c => char.IsLetterOrDigit(c) || c == '.').ToArray());
        if (string.IsNullOrEmpty(ext) || ext.Length > 6)
        {
            ext = i.Mime switch
            {
                "image/jpeg" => ".jpg", "image/heic" => ".heic", "image/heif" => ".heif",
                "image/png" => ".png", "image/webp" => ".webp", _ => ".bin"
            };
        }
        var t = i.CapturedMs is long ms && ms > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString("HHmmss") : "000000";
        return $"{i.Seq:D4}_{t}{(suffix == null ? "" : "_" + suffix)}{ext.ToLowerInvariant()}";
    }
}
