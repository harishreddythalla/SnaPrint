using System.Diagnostics;
using System.Net;
using System.Text;

namespace SnapToPrint.Core;

public enum Level { Good, Warn, Bad }

public sealed record EngineStatus(
    string Headline, Level Level, string PhoneAddress, string EventName, string EventFolder,
    long Received, int Pending, int Failed, DateTime? LastReceiptUtc);

/// <summary>Background loop: find phone, handshake, pull queue, commit files, acknowledge.</summary>
public sealed class SyncEngine
{
    private readonly ConfigStore _cfg;
    private readonly ReceiptDb _db;
    private readonly AppLog _log;
    private readonly HttpClient _http;
    private readonly PhoneClient _phone;
    private readonly PhotoReceiver _rx;
    private readonly HashSet<string> _finishAcked = new();
    private CancellationTokenSource? _cts;
    private Task? _task;
    private Uri? _host;
    private int _fails;
    private volatile bool _reconnect;
    private volatile EngineStatus _status;

    public EngineStatus Status => _status;

    public SyncEngine(ConfigStore cfg, ReceiptDb db, AppLog log, HttpMessageHandler? handler = null)
    {
        _cfg = cfg; _db = db; _log = log;
        _http = new HttpClient(handler ?? new SocketsHttpHandler { UseProxy = false, ConnectTimeout = TimeSpan.FromSeconds(3) })
        { Timeout = TimeSpan.FromSeconds(30) };
        _phone = new PhoneClient(_http);
        _rx = new PhotoReceiver(_phone, db, () => cfg.DestRoot, log);
        _status = Make("STARTING…", Level.Warn);
    }

    public void Start()
    {
        if (_task != null) return;
        _cts = new CancellationTokenSource();
        _task = Task.Run(() => RunAsync(_cts.Token));
    }

    public void Stop() { _cts?.Cancel(); }
    public void Reconnect() { _reconnect = true; }

    private EngineStatus Make(string headline, Level level, SessionInfo? s = null, int pending = 0, int failed = 0)
    {
        var sid = s?.SessionId;
        var live = s != null && s.State is "STARTING" or "ACTIVE" or "FINISHING";
        return new EngineStatus(
            headline, level, _host?.Host ?? "-", live ? s!.Label ?? "" : "-",
            sid != null ? _db.GetFolder(sid) ?? "" : "",
            sid != null ? _db.CountReceipts(sid) : 0, pending, failed, _db.LastReceiptUtc());
    }

    private async Task RunAsync(CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    if (string.IsNullOrEmpty(_cfg.Token))
                    {
                        _status = Make("SETUP NEEDED · enter the pairing code shown on the phone", Level.Bad);
                        await Task.Delay(1000, ct);
                        continue;
                    }
                    if (_reconnect) { _host = null; _reconnect = false; }
                    if (_host == null)
                    {
                        _host = await FindPhoneAsync(ct);
                        if (_host == null)
                        {
                            _status = Make("PHONE NOT FOUND · turn on the hotspot, connect this laptop, open the phone app", Level.Bad);
                            await Task.Delay(2000, ct);
                            continue;
                        }
                        _log.Info("phone found at " + _host.Host);
                    }
                    _phone.Base = _host;
                    _phone.Token = _cfg.Token;

                    var s = await _phone.GetSessionAsync(ct);
                    _fails = 0;
                    if (s.State == "STARTING" && !s.ReadyAck && s.SessionId != null)
                    {
                        try
                        {
                            var folder = _rx.PrepareSessionFolder(s.SessionId, s.Label ?? "", s.StartMs);
                            await _phone.ReadyAsync(s.SessionId, folder, ct);
                            _log.Info("event ready: " + folder);
                            s = s with { ReadyAck = true };
                        }
                        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
                        {
                            _log.Error("cannot prepare event folder: " + e.Message);
                            _status = Make("DESTINATION FOLDER UNAVAILABLE · " + e.Message, Level.Bad, s);
                            await Task.Delay(3000, ct);
                            continue;
                        }
                    }

                    var q = await _phone.NextAsync(ct);
                    UpdateStatus(s, q);
                    if (q.Item == null)
                    {
                        if (s.State == "FINISHED" && q.Pending == 0 && s.SessionId != null && _finishAcked.Add(s.SessionId))
                            await _phone.FinishAckAsync(s.SessionId, ct);
                        await Task.Delay(500, ct);
                        continue;
                    }

                    var res = await _rx.ReceiveAsync(q.Item, ct);
                    switch (res.Outcome)
                    {
                        case ReceiveOutcome.Paused:
                            _log.Error(res.Message ?? "paused");
                            _status = Make("PAUSED · " + res.Message, Level.Bad, s, q.Pending, q.Failed);
                            await Task.Delay(3000, ct);
                            break;
                        case ReceiveOutcome.Rejected:
                            await Task.Delay(300, ct);
                            break;
                    }
                }
                catch (PhoneApiException e) when (e.Status == 401 && !ct.IsCancellationRequested)
                {
                    _log.Error("phone rejected the pairing code");
                    _status = Make("PAIRING CODE REJECTED · re-enter the code shown on the phone", Level.Bad);
                    await Task.Delay(3000, ct);
                }
                catch (Exception e) when (!ct.IsCancellationRequested)
                {
                    _fails++;
                    _log.Error("link problem: " + e.Message);
                    if (_fails >= 3) _host = null; // re-run discovery
                    _status = Make("CONNECTION LOST · retrying automatically (photos stay safe on the phone)", Level.Warn);
                    await Task.Delay(Math.Min(10_000, 1000 * _fails), ct);
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    private void UpdateStatus(SessionInfo s, QueueResponse q)
    {
        var received = s.SessionId != null ? _db.CountReceipts(s.SessionId) : 0;
        var (text, lvl) = s.State switch
        {
            "STARTING" => ("PREPARING EVENT…", Level.Warn),
            "ACTIVE" => ($"EVENT ACTIVE · RECEIVING · {received} RECEIVED · {q.Pending} PENDING", Level.Good),
            "FINISHING" => ($"EVENT FINISHING · {q.Pending} PENDING", Level.Warn),
            _ => q.Pending > 0 ? ($"EVENT ENDED · {q.Pending} STILL TO RECEIVE", Level.Warn) : ("CONNECTED · READY FOR EVENT", Level.Good)
        };
        if (q.Failed > 0) { text += $" · {q.Failed} FAILED"; lvl = Level.Warn; }
        _status = Make(text, lvl, s, q.Pending, q.Failed);
    }

    private async Task<Uri?> FindPhoneAsync(CancellationToken ct)
    {
        foreach (var ip in PhoneLocator.Candidates(_cfg.ManualIp, _cfg.LastHost))
        {
            var u = new Uri($"http://{ip}:{PhoneLocator.Port}/");
            var r = await PhoneClient.ProbeAsync(_http, u, _cfg.Token, ct, TimeSpan.FromSeconds(1.5));
            if (r.Found) { _cfg.SetLastHost(ip.ToString()); return u; }
        }
        return null;
    }

    /// <summary>Connectivity diagnostic (feasibility gate): shows adapters, probes candidates, round-trips a request.</summary>
    public async Task<string> DiagnoseAsync()
    {
        var sb = new StringBuilder();
        sb.Append(PhoneLocator.Describe());
        var cands = PhoneLocator.Candidates(_cfg.ManualIp, _cfg.LastHost);
        if (cands.Count == 0) sb.AppendLine("No candidate phone addresses (no Wi-Fi gateway). Try entering the phone's IP manually.");
        foreach (var ip in cands)
        {
            var u = new Uri($"http://{ip}:{PhoneLocator.Port}/");
            var sw = Stopwatch.StartNew();
            var r = await PhoneClient.ProbeAsync(_http, u, _cfg.Token, CancellationToken.None, TimeSpan.FromSeconds(3));
            sw.Stop();
            sb.AppendLine($"{u}: {(r.Found ? "PHONE APP REACHED" : "no answer")} in {sw.ElapsedMilliseconds} ms" +
                          (r.Found ? $", pairing code {(r.Authorized ? "accepted" : "NOT accepted")}" : ""));
            if (r.Found && r.Authorized)
            {
                try
                {
                    var p = new PhoneClient(_http) { Base = u, Token = _cfg.Token };
                    var s = await p.GetSessionAsync(CancellationToken.None);
                    sb.AppendLine($"  authenticated round-trip OK; phone session state: {s.State}");
                }
                catch (Exception e) { sb.AppendLine("  authenticated request failed: " + e.Message); }
            }
        }
        return sb.ToString();
    }
}
