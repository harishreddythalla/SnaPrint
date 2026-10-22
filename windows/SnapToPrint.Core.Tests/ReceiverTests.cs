using System.Net;
using System.Security.Cryptography;
using SnapToPrint.Core;
using Xunit;

namespace SnapToPrint.Core.Tests;

public sealed class FakePhone : HttpMessageHandler
{
    public byte[] Content = Array.Empty<byte>();
    public bool Truncate, ThrowMidTransfer;
    public int FailAckTimes, AckCount, NackCount;

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage r, CancellationToken ct)
    {
        var path = r.RequestUri!.AbsolutePath;
        if (path.EndsWith("/content"))
        {
            if (ThrowMidTransfer) throw new HttpRequestException("connection reset");
            var body = Truncate ? Content[..(Content.Length / 2)] : Content;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(body) });
        }
        if (path.EndsWith("/ack"))
        {
            if (FailAckTimes > 0) { FailAckTimes--; throw new HttpRequestException("lost ack"); }
            AckCount++;
        }
        if (path.EndsWith("/nack")) NackCount++;
        return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"ok\":true}") });
    }
}

public sealed class ReceiverTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "stp-" + Guid.NewGuid().ToString("N"));
    private readonly FakePhone _fake = new();
    private readonly PhotoReceiver _rx;
    private readonly ReceiptDb _db;
    private readonly byte[] _bytes = RandomNumberGenerator.GetBytes(20_000);

    public ReceiverTests()
    {
        Directory.CreateDirectory(_dir);
        _db = new ReceiptDb(Path.Combine(_dir, "r.db"));
        var phone = new PhoneClient(new HttpClient(_fake)) { Base = new Uri("http://phone/"), Token = "T" };
        _fake.Content = _bytes;
        _rx = new PhotoReceiver(phone, _db, () => Path.Combine(_dir, "root"), new AppLog(null));
    }

    public void Dispose() { try { Directory.Delete(_dir, true); } catch { } }

    private PhotoItem Item(string id = "photo-0001", string session = "sess-1", int seq = 1, string? sha = null, long? size = null) =>
        new(id, session, "Wedding", 1_700_000_000_000, seq, "IMG_1.jpg", "image/jpeg", size ?? _bytes.Length,
            1_700_000_005_000, "DATE_TAKEN", sha ?? Convert.ToHexString(SHA256.HashData(_bytes)).ToLowerInvariant());

    private string[] Finals() => Directory.Exists(Path.Combine(_dir, "root"))
        ? Directory.GetFiles(Path.Combine(_dir, "root"), "*.jpg", SearchOption.AllDirectories) : Array.Empty<string>();

    private string[] Parts() => Directory.Exists(Path.Combine(_dir, "root", ".incoming"))
        ? Directory.GetFiles(Path.Combine(_dir, "root", ".incoming"), "*.part") : Array.Empty<string>();

    [Fact]
    public async Task CommitsVerifiedFileAndAcknowledges()
    {
        var r = await _rx.ReceiveAsync(Item(), CancellationToken.None);
        Assert.Equal(ReceiveOutcome.Committed, r.Outcome);
        Assert.Single(Finals());
        Assert.Equal(_bytes, File.ReadAllBytes(Finals()[0]));
        Assert.Equal(1, _fake.AckCount);
        Assert.Empty(Parts());
    }

    [Fact]
    public async Task DuplicateRequestCreatesNoSecondFile()
    {
        await _rx.ReceiveAsync(Item(), CancellationToken.None);
        var r2 = await _rx.ReceiveAsync(Item(), CancellationToken.None);
        Assert.Equal(ReceiveOutcome.AlreadyCommitted, r2.Outcome);
        Assert.Single(Finals());
        Assert.Equal(2, _fake.AckCount);
    }

    [Fact]
    public async Task ChecksumMismatchIsNeverAcknowledgedOrCommitted()
    {
        var r = await _rx.ReceiveAsync(Item(sha: new string('0', 64)), CancellationToken.None);
        Assert.Equal(ReceiveOutcome.Rejected, r.Outcome);
        Assert.Empty(Finals());
        Assert.Equal(0, _fake.AckCount);
        Assert.Equal(1, _fake.NackCount);
    }

    [Fact]
    public async Task TruncatedBodyIsRejectedAndNeverAppearsComplete()
    {
        _fake.Truncate = true;
        var r = await _rx.ReceiveAsync(Item(), CancellationToken.None);
        Assert.Equal(ReceiveOutcome.Rejected, r.Outcome);
        Assert.Empty(Finals());
        Assert.Empty(Parts());
        Assert.Equal(0, _fake.AckCount);
    }

    [Fact]
    public async Task InterruptedTransferLeavesNothingThenRestartsCleanly()
    {
        _fake.ThrowMidTransfer = true;
        await Assert.ThrowsAsync<HttpRequestException>(() => _rx.ReceiveAsync(Item(), CancellationToken.None));
        Assert.Empty(Finals());
        Assert.Empty(Parts());
        _fake.ThrowMidTransfer = false;
        var r = await _rx.ReceiveAsync(Item(), CancellationToken.None);
        Assert.Equal(ReceiveOutcome.Committed, r.Outcome);
        Assert.Single(Finals());
    }

    [Fact]
    public async Task CrashBetweenCommitAndAckIsRecoveredIdempotently()
    {
        _fake.FailAckTimes = 1;
        await Assert.ThrowsAsync<HttpRequestException>(() => _rx.ReceiveAsync(Item(), CancellationToken.None));
        Assert.Single(Finals()); // committed before the ack was attempted
        var r = await _rx.ReceiveAsync(Item(), CancellationToken.None);
        Assert.Equal(ReceiveOutcome.AlreadyCommitted, r.Outcome);
        Assert.Single(Finals());
        Assert.Equal(1, _fake.AckCount);
    }

    [Fact]
    public async Task OrphanFileWithoutReceiptIsAdoptedNotDuplicated()
    {
        // Simulates a crash after the file move but before the receipt row was written.
        var item = Item();
        var folder = _rx.PrepareSessionFolder(item.SessionId, item.SessionLabel, item.SessionStartMs);
        File.WriteAllBytes(Path.Combine(folder, FolderNaming.FileName(item)), _bytes);
        var r = await _rx.ReceiveAsync(item, CancellationToken.None);
        Assert.Equal(ReceiveOutcome.AlreadyCommitted, r.Outcome);
        Assert.Single(Finals());
    }

    [Fact]
    public async Task DifferentSessionsGoToDifferentFolders()
    {
        await _rx.ReceiveAsync(Item("p1", "sess-A", 1), CancellationToken.None);
        await _rx.ReceiveAsync(Item("p2", "sess-B", 1), CancellationToken.None);
        var folders = Finals().Select(Path.GetDirectoryName).Distinct().ToList();
        Assert.Equal(2, folders.Count);
    }

    [Fact]
    public async Task InsufficientDiskSpacePausesInsteadOfFailing()
    {
        var r = await _rx.ReceiveAsync(Item(size: long.MaxValue / 4), CancellationToken.None);
        Assert.Equal(ReceiveOutcome.Paused, r.Outcome);
        Assert.Contains("DISK FULL", r.Message);
        Assert.Empty(Finals());
    }

    [Fact]
    public void FolderNamesAreSafeAndDeterministic()
    {
        var n = FolderNaming.EventFolderName(1_700_000_000_000, "Wed/ding: 2026*?");
        Assert.Equal(n, FolderNaming.EventFolderName(1_700_000_000_000, "Wed/ding: 2026*?"));
        Assert.DoesNotContain(Path.GetInvalidFileNameChars().Where(c => c != '_'), c => n.Contains(c));
        Assert.StartsWith("Event_", n);
    }

    [Fact]
    public void HeicExtensionIsPreservedNotRenamed()
    {
        var i = Item() with { FileName = "IMG_9.HEIC", Mime = "image/heic" };
        Assert.EndsWith(".heic", FolderNaming.FileName(i));
    }
}
