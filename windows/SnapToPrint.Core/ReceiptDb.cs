using Microsoft.Data.Sqlite;

namespace SnapToPrint.Core;

/// <summary>Source of truth for files committed to disk on the Windows side.</summary>
public sealed class ReceiptDb
{
    private readonly string _cs;

    public ReceiptDb(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        _cs = new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString();
        using var c = Open();
        Exec(c, "PRAGMA journal_mode=WAL;");
        Exec(c, "CREATE TABLE IF NOT EXISTS sessions(session_id TEXT PRIMARY KEY, folder TEXT NOT NULL)");
        Exec(c, "CREATE TABLE IF NOT EXISTS receipts(photo_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, seq INTEGER, path TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL, committed_utc TEXT NOT NULL)");
    }

    private SqliteConnection Open()
    {
        var c = new SqliteConnection(_cs);
        c.Open();
        Exec(c, "PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;");
        return c;
    }

    private static void Exec(SqliteConnection c, string sql)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    public string? GetFolder(string sessionId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT folder FROM sessions WHERE session_id=$s";
        cmd.Parameters.AddWithValue("$s", sessionId);
        return cmd.ExecuteScalar() as string;
    }

    public void SetFolder(string sessionId, string folder)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT OR IGNORE INTO sessions(session_id, folder) VALUES($s,$f)";
        cmd.Parameters.AddWithValue("$s", sessionId);
        cmd.Parameters.AddWithValue("$f", folder);
        cmd.ExecuteNonQuery();
    }

    public (string Path, string Sha, long Size)? GetReceipt(string photoId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT path, sha256, size FROM receipts WHERE photo_id=$p";
        cmd.Parameters.AddWithValue("$p", photoId);
        using var r = cmd.ExecuteReader();
        return r.Read() ? (r.GetString(0), r.GetString(1), r.GetInt64(2)) : null;
    }

    public void AddReceipt(string photoId, string sessionId, int seq, string path, string sha, long size)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT OR IGNORE INTO receipts(photo_id, session_id, seq, path, sha256, size, committed_utc) VALUES($p,$s,$q,$a,$h,$z,$t)";
        cmd.Parameters.AddWithValue("$p", photoId);
        cmd.Parameters.AddWithValue("$s", sessionId);
        cmd.Parameters.AddWithValue("$q", seq);
        cmd.Parameters.AddWithValue("$a", path);
        cmd.Parameters.AddWithValue("$h", sha);
        cmd.Parameters.AddWithValue("$z", size);
        cmd.Parameters.AddWithValue("$t", DateTime.UtcNow.ToString("O"));
        cmd.ExecuteNonQuery();
    }

    public long CountReceipts(string sessionId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM receipts WHERE session_id=$s";
        cmd.Parameters.AddWithValue("$s", sessionId);
        return Convert.ToInt64(cmd.ExecuteScalar());
    }

    public DateTime? LastReceiptUtc()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT MAX(committed_utc) FROM receipts";
        return cmd.ExecuteScalar() is string s
            ? DateTime.Parse(s, null, System.Globalization.DateTimeStyles.RoundtripKind) : null;
    }
}
