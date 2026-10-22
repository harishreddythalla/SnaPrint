using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace SnapToPrint.Core;

public sealed class PhoneApiException : Exception
{
    public int Status { get; }
    public PhoneApiException(string message, int status) : base(message) { Status = status; }
}

public sealed record ProbeResult(bool Found, bool Authorized);

/// <summary>Client for protocol v1 (protocol/protocol-v1.md).</summary>
public class PhoneClient
{
    private readonly HttpClient _http;
    public Uri? Base { get; set; }
    public string Token { get; set; } = "";

    public PhoneClient(HttpClient http) { _http = http; }

    private HttpRequestMessage Req(HttpMethod m, string path, object? body = null)
    {
        if (Base == null) throw new InvalidOperationException("no phone address");
        var r = new HttpRequestMessage(m, new Uri(Base, path));
        r.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
        if (body != null) r.Content = new StringContent(JsonSerializer.Serialize(body, JsonOpts.Web), Encoding.UTF8, "application/json");
        else if (m == HttpMethod.Post) r.Content = new StringContent("{}", Encoding.UTF8, "application/json");
        return r;
    }

    private async Task<T> Call<T>(HttpRequestMessage r, CancellationToken ct)
    {
        using var resp = await _http.SendAsync(r, ct);
        if (!resp.IsSuccessStatusCode) throw new PhoneApiException($"{(int)resp.StatusCode} {resp.ReasonPhrase}", (int)resp.StatusCode);
        var s = await resp.Content.ReadAsStringAsync(ct);
        return JsonSerializer.Deserialize<T>(s, JsonOpts.Web)!;
    }

    public static async Task<ProbeResult> ProbeAsync(HttpClient http, Uri baseUri, string token, CancellationToken ct, TimeSpan timeout)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        cts.CancelAfter(timeout);
        try
        {
            var req = new HttpRequestMessage(HttpMethod.Get, new Uri(baseUri, "api/v1/health"));
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var resp = await http.SendAsync(req, cts.Token);
            if (!resp.IsSuccessStatusCode) return new ProbeResult(false, false);
            using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync(cts.Token));
            var ok = doc.RootElement.TryGetProperty("app", out var a) && a.GetString() == "snap-to-print";
            var auth = doc.RootElement.TryGetProperty("authorized", out var z) && z.ValueKind == JsonValueKind.True;
            return new ProbeResult(ok, ok && auth);
        }
        catch (Exception) when (!ct.IsCancellationRequested) { return new ProbeResult(false, false); }
    }

    public Task<SessionInfo> GetSessionAsync(CancellationToken ct) => Call<SessionInfo>(Req(HttpMethod.Get, "api/v1/session/current"), ct);
    public Task<QueueResponse> NextAsync(CancellationToken ct) => Call<QueueResponse>(Req(HttpMethod.Get, "api/v1/queue/next"), ct);
    public Task ReadyAsync(string sessionId, string folder, CancellationToken ct) =>
        Call<JsonElement>(Req(HttpMethod.Post, $"api/v1/session/{sessionId}/ready", new { folder }), ct);
    public Task FinishAckAsync(string sessionId, CancellationToken ct) =>
        Call<JsonElement>(Req(HttpMethod.Post, $"api/v1/session/{sessionId}/finish-ack"), ct);
    public virtual Task AckAsync(string photoId, string sha256, long size, CancellationToken ct) =>
        Call<JsonElement>(Req(HttpMethod.Post, $"api/v1/photos/{photoId}/ack", new { sha256, size }), ct);
    public virtual Task NackAsync(string photoId, string reason, bool retry, CancellationToken ct) =>
        Call<JsonElement>(Req(HttpMethod.Post, $"api/v1/photos/{photoId}/nack", new { reason, retry }), ct);

    public async Task<HttpResponseMessage> GetContentAsync(string photoId, CancellationToken ct)
    {
        var resp = await _http.SendAsync(Req(HttpMethod.Get, $"api/v1/photos/{photoId}/content"), HttpCompletionOption.ResponseHeadersRead, ct);
        if (!resp.IsSuccessStatusCode)
        {
            var st = (int)resp.StatusCode;
            resp.Dispose();
            throw new PhoneApiException($"content request failed: {st}", st);
        }
        return resp;
    }
}
