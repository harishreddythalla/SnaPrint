using System.Text.Json;

namespace SnapToPrint.Core;

public record PhotoItem(
    string PhotoId, string SessionId, string SessionLabel, long SessionStartMs, int Seq,
    string FileName, string? Mime, long Size, long? CapturedMs, string CaptureSource, string Sha256);

public record QueueResponse(PhotoItem? Item, int Pending, int Failed);

public record SessionInfo(string State, string? SessionId = null, string? Label = null, long StartMs = 0, bool ReadyAck = false);

public static class JsonOpts
{
    public static readonly JsonSerializerOptions Web = new(JsonSerializerDefaults.Web);
}
