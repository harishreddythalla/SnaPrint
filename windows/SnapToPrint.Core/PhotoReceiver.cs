using System.Security.Cryptography;

namespace SnapToPrint.Core;

public enum ReceiveOutcome { Committed, AlreadyCommitted, Rejected, Paused }

public sealed record ReceiveResult(ReceiveOutcome Outcome, string? Message = null, string? FinalPath = null);

/// <summary>
/// Download to a temp file, verify size + SHA-256, flush, atomically move into the event folder,
/// persist the receipt, and only then acknowledge. Retries are idempotent.
/// </summary>
public sealed class PhotoReceiver
{
    private readonly PhoneClient _phone;
    private readonly ReceiptDb _db;
    private readonly Func<string> _root;
    private readonly AppLog _log;

    public long MinFreeBytes { get; set; } = 50L * 1024 * 1024;

    public PhotoReceiver(PhoneClient phone, ReceiptDb db, Func<string> root, AppLog log)
    {
        _phone = phone; _db = db; _root = root; _log = log;
    }

    private string Incoming()
    {
        var dir = Path.Combine(_root(), ".incoming");
        if (!Directory.Exists(dir))
        {
            Directory.CreateDirectory(dir);
            try { File.SetAttributes(dir, FileAttributes.Hidden); } catch { }
        }
        return dir;
    }

    /// <summary>Creates the event folder (if needed) and proves the destination is writable.</summary>
    public string PrepareSessionFolder(string sessionId, string label, long startMs)
    {
        var folder = _db.GetFolder(sessionId) ?? Path.Combine(_root(), FolderNaming.EventFolderName(startMs, label));
        Directory.CreateDirectory(folder);
        var probe = Path.Combine(Incoming(), ".write-test-" + Guid.NewGuid().ToString("N"));
        File.WriteAllText(probe, "x");
        File.Delete(probe);
        _db.SetFolder(sessionId, folder);
        return folder;
    }

    public async Task<ReceiveResult> ReceiveAsync(PhotoItem item, CancellationToken ct)
    {
        string folder;
        try { folder = PrepareSessionFolder(item.SessionId, item.SessionLabel, item.SessionStartMs); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return new ReceiveResult(ReceiveOutcome.Paused, "DESTINATION FOLDER UNAVAILABLE · " + e.Message);
        }

        // Already committed earlier (e.g. our acknowledgement was lost): just acknowledge again.
        var existingReceipt = _db.GetReceipt(item.PhotoId);
        if (existingReceipt is { } rcpt)
        {
            await _phone.AckAsync(item.PhotoId, rcpt.Sha, rcpt.Size, ct);
            return new ReceiveResult(ReceiveOutcome.AlreadyCommitted, null, rcpt.Path);
        }

        // Choose a target name; adopt an identical file left by a crash between commit and receipt.
        string? target = null;
        for (var n = 0; n < 50 && target == null; n++)
        {
            var suffix = n == 0 ? null : (n == 1 ? item.PhotoId[..Math.Min(8, item.PhotoId.Length)] : $"{item.PhotoId[..Math.Min(8, item.PhotoId.Length)]}_{n}");
            var candidate = Path.Combine(folder, FolderNaming.FileName(item, suffix));
            if (!File.Exists(candidate)) { target = candidate; break; }
            var sha = await ShaOfFileAsync(candidate, ct);
            if (string.Equals(sha, item.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                var size = new FileInfo(candidate).Length;
                _db.AddReceipt(item.PhotoId, item.SessionId, item.Seq, candidate, sha, size);
                await _phone.AckAsync(item.PhotoId, sha, size, ct);
                return new ReceiveResult(ReceiveOutcome.AlreadyCommitted, null, candidate);
            }
        }
        if (target == null) return new ReceiveResult(ReceiveOutcome.Paused, "COULD NOT CHOOSE A FILE NAME");

        try
        {
            var root = Path.GetPathRoot(Path.GetFullPath(folder));
            if (root != null && new DriveInfo(root).AvailableFreeSpace < item.Size + MinFreeBytes)
                return new ReceiveResult(ReceiveOutcome.Paused, "DISK FULL · free up space on the laptop");
        }
        catch (ArgumentException) { /* e.g. UNC path: skip the pre-check */ }

        var part = Path.Combine(Incoming(), item.PhotoId + ".part");
        try
        {
            string actual;
            long total = 0;
            using (var resp = await _phone.GetContentAsync(item.PhotoId, ct))
            await using (var src = await resp.Content.ReadAsStreamAsync(ct))
            {
                using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                await using (var fs = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous))
                {
                    var buf = new byte[81920];
                    int read;
                    while ((read = await src.ReadAsync(buf.AsMemory(0, buf.Length), ct)) > 0)
                    {
                        await fs.WriteAsync(buf.AsMemory(0, read), ct);
                        hasher.AppendData(buf, 0, read);
                        total += read;
                    }
                    await fs.FlushAsync(ct);
                    fs.Flush(true); // force to disk
                }
                actual = Convert.ToHexString(hasher.GetHashAndReset()).ToLowerInvariant();
            }

            if (total != item.Size || !string.Equals(actual, item.Sha256, StringComparison.OrdinalIgnoreCase))
            {
                TryDelete(part);
                var msg = $"photo {item.Seq}: size/checksum mismatch (got {total} bytes); asking phone to retry";
                _log.Error(msg);
                await _phone.NackAsync(item.PhotoId, "size or checksum mismatch", true, ct);
                return new ReceiveResult(ReceiveOutcome.Rejected, msg);
            }

            File.Move(part, target, false); // atomic on the same volume; never overwrites
            _db.AddReceipt(item.PhotoId, item.SessionId, item.Seq, target, actual, total);
            _log.Info($"committed photo {item.Seq} -> {Path.GetFileName(target)}");
            await _phone.AckAsync(item.PhotoId, actual, total, ct); // if this fails, the next request re-acks
            return new ReceiveResult(ReceiveOutcome.Committed, null, target);
        }
        catch (IOException ex) when (IsDiskFull(ex))
        {
            TryDelete(part);
            return new ReceiveResult(ReceiveOutcome.Paused, "DISK FULL · free up space on the laptop");
        }
        catch (UnauthorizedAccessException ex)
        {
            TryDelete(part);
            return new ReceiveResult(ReceiveOutcome.Paused, "DESTINATION FOLDER NOT WRITABLE · " + ex.Message);
        }
        catch
        {
            TryDelete(part); // interrupted: the next attempt restarts from the beginning
            throw;
        }
    }

    private static bool IsDiskFull(IOException ex) => (ex.HResult & 0xFFFF) is 112 or 39;

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static async Task<string> ShaOfFileAsync(string path, CancellationToken ct)
    {
        await using var fs = File.OpenRead(path);
        return Convert.ToHexString(await SHA256.HashDataAsync(fs, ct)).ToLowerInvariant();
    }
}
