using System.Buffers;
using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace ProtoHostCs;

internal static class Program
{
    private static readonly byte[] Wbh1Magic = [0x57, 0x42, 0x48, 0x31]; // WBH1
    private static readonly byte[] WbtpMagic = [0x57, 0x42, 0x54, 0x50]; // WBTP
    private static readonly byte[] Wbs1Magic = [0x57, 0x42, 0x53, 0x31]; // WBS1

    private const int Wbh1HeaderBytes = 24;
    private const int WbtpHeaderBytes = 22;
    private const int Wbs1HeaderBytes = 16;
    private const int Wbs1HeaderMaxBytes = 4096;
    private const int MaxPayloadBytes = 2 * 1024 * 1024;
    private const int NalTypeNonIdr = 1;
    private const int NalTypeIdr = 5;
    private const int NalTypeSei = 6;
    private const int NalTypeSps = 7;
    private const int NalTypePps = 8;

    private static int Main()
    {
        var cfg = RelayConfig.FromEnvironment();
        if (!cfg.H264Enabled)
        {
            Log("PROTO_H264 is not enabled; C# backend currently supports H264 mode only.");
            return 2;
        }

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        Process? portal = null;
        try
        {
            portal = PortalProcess.StartIfEnabled(cfg);
            RunRelayLoop(cfg, cts.Token);
            return 0;
        }
        catch (OperationCanceledException)
        {
            return 0;
        }
        catch (Exception ex)
        {
            Log($"fatal error: {ex.Message}");
            return 1;
        }
        finally
        {
            if (portal is { HasExited: false })
            {
                try { portal.Kill(entireProcessTree: true); } catch { /* ignore */ }
            }
        }
    }

    private static void RunRelayLoop(RelayConfig cfg, CancellationToken token)
    {
        ulong seq = 1;
        while (!token.IsCancellationRequested)
        {
            using var sink = ConnectWithRetry(cfg.SinkHost, cfg.SinkPort, "sink", token, 500);
            ConfigureSocket(
                sink.Client,
                isSource: false,
                sourceReceiveTimeoutMs: cfg.SourceReceiveTimeoutMs,
                sinkSendTimeoutMs: cfg.SinkSendTimeoutMs);
            Log($"connected sink {cfg.SinkHost}:{cfg.SinkPort}");

            using var source = ConnectWithRetry(cfg.SourceHost, cfg.SourcePort, "source", token, 350);
            ConfigureSocket(
                source.Client,
                isSource: true,
                sourceReceiveTimeoutMs: cfg.SourceReceiveTimeoutMs,
                sinkSendTimeoutMs: cfg.SinkSendTimeoutMs);
            Log($"connected source {cfg.SourceHost}:{cfg.SourcePort} framed={cfg.SourceFramed}");

            try
            {
                using var sourceStream = source.GetStream();
                using var sinkStream = sink.GetStream();
                if (cfg.SourceFramed)
                {
                    PumpFramed(sourceStream, sinkStream, ref seq, token);
                }
                else
                {
                    throw new InvalidOperationException(
                        "Annex-B relay is not enabled in C# backend yet; set PROTO_H264_SOURCE_FRAMED=1.");
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                var detail = ex.InnerException?.Message;
                Log(detail is null
                    ? $"relay disconnected: {ex.GetType().Name}: {ex.Message}"
                    : $"relay disconnected: {ex.GetType().Name}: {ex.Message} | inner={detail}");
                SleepWithCancel(250, token);
            }
        }
    }

    private static void PumpFramed(NetworkStream source, NetworkStream sink, ref ulong seq, CancellationToken token)
    {
        var header = new byte[WbtpHeaderBytes];
        var payload = ArrayPool<byte>.Shared.Rent(256 * 1024);
        var stats = new StreamStats();

        try
        {
            ReadFirstFramedHeader(source, header, token);
            while (!token.IsCancellationRequested)
            {
                var payloadLen = ParseWbtpHeader(header, out var captureTsMs);
                if (payloadLen > payload.Length)
                {
                    ArrayPool<byte>.Shared.Return(payload);
                    payload = ArrayPool<byte>.Shared.Rent(payloadLen);
                }

                ReadExact(source, payload, 0, payloadLen, token);
                var unitsSent = WriteWbh1AnnexBUnits(
                    sink, ref seq, captureTsMs, payload, payloadLen, stats, token);
                if (unitsSent == 0)
                {
                    WriteWbh1Frame(sink, seq, captureTsMs, payload, 0, payloadLen, token);
                    stats.Record(payloadLen, seq);
                    seq = NextSeq(seq);
                }
                // unitsSent < 0 means "NALs parsed but all were filtered (aux-only AU)".

                ReadExact(source, header, 0, WbtpHeaderBytes, token);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(payload);
        }
    }

    private static void ReadFirstFramedHeader(NetworkStream source, byte[] header, CancellationToken token)
    {
        var first4 = new byte[4];
        ReadExact(source, first4, 0, first4.Length, token);

        if (first4.AsSpan().SequenceEqual(Wbs1Magic))
        {
            ConsumeWbs1Hello(source, token);
            ReadExact(source, header, 0, WbtpHeaderBytes, token);
            return;
        }

        Buffer.BlockCopy(first4, 0, header, 0, first4.Length);
        ReadExact(source, header, first4.Length, WbtpHeaderBytes - first4.Length, token);
    }

    private static void ConsumeWbs1Hello(NetworkStream source, CancellationToken token)
    {
        var rest = new byte[Wbs1HeaderBytes - 4];
        ReadExact(source, rest, 0, rest.Length, token);

        var version = rest[0];
        var flags = rest[1];
        var declaredLen = BinaryPrimitives.ReadUInt16BigEndian(rest.AsSpan(2, 2));
        var session = BinaryPrimitives.ReadUInt64BigEndian(rest.AsSpan(4, 8));

        if (declaredLen < Wbs1HeaderBytes || declaredLen > Wbs1HeaderMaxBytes)
        {
            throw new IOException($"bad WBS1 len={declaredLen}");
        }

        var extra = declaredLen - Wbs1HeaderBytes;
        if (extra > 0)
        {
            DiscardExact(source, extra, token);
        }

        Log($"source hello WBS1 version={version} flags={flags} len={declaredLen} session=0x{session:x16}");
    }

    private static void DiscardExact(NetworkStream stream, int len, CancellationToken token)
    {
        var scratch = ArrayPool<byte>.Shared.Rent(512);
        try
        {
            var left = len;
            while (left > 0)
            {
                var take = Math.Min(left, scratch.Length);
                ReadExact(stream, scratch, 0, take, token);
                left -= take;
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(scratch);
        }
    }

    private static int ParseWbtpHeader(byte[] header, out ulong captureTsMs)
    {
        if (!header.AsSpan(0, 4).SequenceEqual(WbtpMagic))
        {
            throw new IOException("bad WBTP magic");
        }
        if (header[4] != 1)
        {
            throw new IOException($"bad WBTP version={header[4]}");
        }

        var captureTsUs = BinaryPrimitives.ReadUInt64BigEndian(header.AsSpan(10, 8));
        captureTsMs = captureTsUs / 1000UL;

        var payloadLen = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(18, 4));
        if (payloadLen <= 0 || payloadLen > MaxPayloadBytes)
        {
            throw new IOException($"bad WBTP payload len={payloadLen}");
        }

        return payloadLen;
    }

    private static void WriteWbh1Frame(
        NetworkStream sink,
        ulong seq,
        ulong tsMs,
        byte[] payload,
        int payloadOffset,
        int payloadLen,
        CancellationToken token)
    {
        Span<byte> header = stackalloc byte[Wbh1HeaderBytes];
        Wbh1Magic.CopyTo(header);
        BinaryPrimitives.WriteUInt64BigEndian(header[4..12], seq);
        BinaryPrimitives.WriteUInt64BigEndian(header[12..20], tsMs);
        BinaryPrimitives.WriteUInt32BigEndian(header[20..24], (uint)payloadLen);

        token.ThrowIfCancellationRequested();
        sink.Write(header);
        sink.Write(payload, payloadOffset, payloadLen);
    }

    private static int WriteWbh1AnnexBUnits(
        NetworkStream sink,
        ref ulong seq,
        ulong tsMs,
        byte[] payload,
        int payloadLen,
        StreamStats stats,
        CancellationToken token)
    {
        var sent = 0;
        var sawNal = false;
        var cursor = 0;
        while (TryFindStartCode(payload, payloadLen, cursor, out var startIdx, out var startLen))
        {
            sawNal = true;
            var searchFrom = startIdx + startLen;
            var unitEnd = TryFindStartCode(payload, payloadLen, searchFrom, out var nextIdx, out _)
                ? nextIdx
                : payloadLen;
            var unitLen = unitEnd - startIdx;
            if (unitLen > 0)
            {
                var nalType = ParseNalType(payload, payloadLen, startIdx, startLen);
                if (ShouldForwardNalType(nalType))
                {
                    WriteWbh1Frame(sink, seq, tsMs, payload, startIdx, unitLen, token);
                    stats.Record(unitLen, seq);
                    seq = NextSeq(seq);
                    sent++;
                }
            }

            if (unitEnd >= payloadLen)
            {
                break;
            }
            cursor = unitEnd;
        }
        if (sent > 0)
        {
            return sent;
        }
        return sawNal ? -1 : 0;
    }

    private static int ParseNalType(byte[] payload, int payloadLen, int startIdx, int startLen)
    {
        var nalHeaderIdx = startIdx + startLen;
        if (nalHeaderIdx < 0 || nalHeaderIdx >= payloadLen)
        {
            return -1;
        }
        return payload[nalHeaderIdx] & 0x1F;
    }

    private static bool ShouldForwardNalType(int nalType)
    {
        return nalType == NalTypeNonIdr
            || nalType == NalTypeIdr
            || nalType == NalTypeSps
            || nalType == NalTypePps
            || nalType == NalTypeSei;
    }

    private static bool TryFindStartCode(
        byte[] payload,
        int payloadLen,
        int from,
        out int index,
        out int len)
    {
        index = 0;
        len = 0;
        if (payloadLen < 3 || from >= payloadLen - 2)
        {
            return false;
        }

        for (var i = Math.Max(0, from); i + 2 < payloadLen; i++)
        {
            if (i + 3 < payloadLen
                && payload[i] == 0
                && payload[i + 1] == 0
                && payload[i + 2] == 0
                && payload[i + 3] == 1)
            {
                index = i;
                len = 4;
                return true;
            }

            if (payload[i] == 0 && payload[i + 1] == 0 && payload[i + 2] == 1)
            {
                index = i;
                len = 3;
                return true;
            }
        }

        return false;
    }

    private static ulong NextSeq(ulong seq)
    {
        return seq == ulong.MaxValue ? 1 : seq + 1;
    }

    private static void ReadExact(
        NetworkStream stream,
        byte[] buffer,
        int offset,
        int len,
        CancellationToken token)
    {
        var read = 0;
        while (read < len)
        {
            token.ThrowIfCancellationRequested();
            try
            {
                var n = stream.Read(buffer, offset + read, len - read);
                if (n == 0)
                {
                    throw new IOException("EOF");
                }
                read += n;
            }
            catch (IOException ex) when (IsSocketTimeout(ex))
            {
                continue;
            }
        }
    }

    private static bool IsSocketTimeout(IOException ex)
    {
        return ex.InnerException is SocketException se
            && (se.SocketErrorCode == SocketError.TimedOut
                || se.SocketErrorCode == SocketError.WouldBlock);
    }

    private static TcpClient ConnectWithRetry(
        string host,
        int port,
        string label,
        CancellationToken token,
        int retryDelayMs)
    {
        uint attempt = 0;
        while (!token.IsCancellationRequested)
        {
            try
            {
                var client = new TcpClient();
                client.Connect(host, port);
                return client;
            }
            catch (Exception ex)
            {
                attempt++;
                if (attempt % 10 == 1)
                {
                    Log($"waiting for {label} {host}:{port} ({ex.Message})");
                }
                SleepWithCancel(retryDelayMs, token);
            }
        }
        throw new OperationCanceledException(token);
    }

    private static void ConfigureSocket(
        Socket socket,
        bool isSource,
        int sourceReceiveTimeoutMs,
        int sinkSendTimeoutMs)
    {
        socket.NoDelay = true;
        socket.Blocking = true;
        socket.SendBufferSize = 512 * 1024;
        socket.ReceiveBufferSize = 1024 * 1024;
        if (isSource)
        {
            socket.ReceiveTimeout = sourceReceiveTimeoutMs;
        }
        else
        {
            socket.SendTimeout = sinkSendTimeoutMs;
        }
    }

    private static void SleepWithCancel(int ms, CancellationToken token)
    {
        if (ms <= 0 || token.IsCancellationRequested)
        {
            return;
        }
        token.WaitHandle.WaitOne(ms);
    }

    private static void Log(string msg)
    {
        Console.WriteLine($"[proto-cs] {msg}");
    }
}

internal sealed record RelayConfig(
    bool H264Enabled,
    bool PortalEnabled,
    bool SourceFramed,
    string SinkHost,
    int SinkPort,
    string SourceHost,
    int SourcePort,
    int SourceReceiveTimeoutMs,
    int SinkSendTimeoutMs,
    string CaptureSize,
    string CaptureFps,
    string CaptureBitrateKbps,
    string CursorMode,
    string PortalScriptPath)
{
    public static RelayConfig FromEnvironment()
    {
        var h264Enabled = EnvTruth("PROTO_H264", defaultValue: false);
        var portalEnabled = EnvTruth("PROTO_PORTAL", defaultValue: true);
        var sourceFramed = EnvTruth("PROTO_H264_SOURCE_FRAMED", defaultValue: h264Enabled);
        var sourcePort = EnvInt("PROTO_H264_SOURCE_PORT", 5500);
        var sourceReceiveTimeoutMs = EnvInt("PROTO_H264_SOURCE_READ_TIMEOUT_MS", 5000);
        // Default 0 = blocking writes; prevents premature disconnects on slow API17 sink.
        var sinkSendTimeoutMs = EnvInt("PROTO_ADB_WRITE_TIMEOUT_MS", 0);
        var (sinkHost, sinkPort) = ParseHostPort(
            Environment.GetEnvironmentVariable("PROTO_ADB_PUSH_ADDR") ?? "127.0.0.1:5006",
            5006);

        return new RelayConfig(
            H264Enabled: h264Enabled,
            PortalEnabled: portalEnabled,
            SourceFramed: sourceFramed,
            SinkHost: sinkHost,
            SinkPort: sinkPort,
            SourceHost: "127.0.0.1",
            SourcePort: sourcePort,
            SourceReceiveTimeoutMs: sourceReceiveTimeoutMs,
            SinkSendTimeoutMs: sinkSendTimeoutMs,
            CaptureSize: Environment.GetEnvironmentVariable("PROTO_CAPTURE_SIZE") ?? "1280x720",
            CaptureFps: Environment.GetEnvironmentVariable("PROTO_CAPTURE_FPS") ?? "30",
            CaptureBitrateKbps: Environment.GetEnvironmentVariable("PROTO_CAPTURE_BITRATE_KBPS") ?? "16000",
            CursorMode: Environment.GetEnvironmentVariable("PROTO_CURSOR_MODE") ?? "embedded",
            PortalScriptPath: ResolvePortalScriptPath());
    }

    private static string ResolvePortalScriptPath()
    {
        var fromEnv = Environment.GetEnvironmentVariable("PROTO_PORTAL_SCRIPT");
        if (!string.IsNullOrWhiteSpace(fromEnv))
        {
            return fromEnv.Trim();
        }
        return Path.GetFullPath(Path.Combine(Environment.CurrentDirectory, "../../host/scripts/stream_wayland_portal_h264.py"));
    }

    private static bool EnvTruth(string name, bool defaultValue)
    {
        var raw = Environment.GetEnvironmentVariable(name);
        if (raw is null)
        {
            return defaultValue;
        }
        return raw == "1"
            || raw.Equals("true", StringComparison.OrdinalIgnoreCase)
            || raw.Equals("yes", StringComparison.OrdinalIgnoreCase)
            || raw.Equals("on", StringComparison.OrdinalIgnoreCase);
    }

    private static int EnvInt(string name, int defaultValue)
    {
        var raw = Environment.GetEnvironmentVariable(name);
        return int.TryParse(raw, out var parsed) ? parsed : defaultValue;
    }

    private static (string Host, int Port) ParseHostPort(string input, int fallbackPort)
    {
        var s = input.Trim();
        var idx = s.LastIndexOf(':');
        if (idx > 0 && idx < s.Length - 1 && int.TryParse(s[(idx + 1)..], out var parsedPort))
        {
            var hostPart = s[..idx].Trim();
            if (hostPart.Length > 0)
            {
                return (hostPart, parsedPort);
            }
        }
        return (s.Length > 0 ? s : "127.0.0.1", fallbackPort);
    }
}

internal sealed class StreamStats
{
    private long _frames;
    private long _bytes;
    private readonly Stopwatch _window = Stopwatch.StartNew();

    public void Record(int payloadLen, ulong seq)
    {
        _frames++;
        _bytes += payloadLen;

        if (_window.ElapsedMilliseconds < 1000)
        {
            return;
        }

        var seconds = Math.Max(0.001, _window.Elapsed.TotalSeconds);
        var fps = _frames / seconds;
        var avgKb = _frames > 0 ? (_bytes / _frames) / 1024 : 0;
        Console.WriteLine($"[proto-cs] WBH1 stats: units={_frames} avg_kb={avgKb} fps={fps:F1} seq={seq}");
        _frames = 0;
        _bytes = 0;
        _window.Restart();
    }
}

internal static class PortalProcess
{
    public static Process? StartIfEnabled(RelayConfig cfg)
    {
        if (!cfg.PortalEnabled)
        {
            ProgramLog("PROTO_PORTAL disabled; not starting portal helper");
            return null;
        }

        if (!File.Exists(cfg.PortalScriptPath))
        {
            ProgramLog($"portal helper script not found: {cfg.PortalScriptPath}");
            return null;
        }

        var psi = new ProcessStartInfo("python3")
        {
            UseShellExecute = false,
            RedirectStandardInput = false,
            RedirectStandardOutput = false,
            RedirectStandardError = false,
            CreateNoWindow = true,
        };

        var argumentPairs = new (string Key, string? Value)[]
        {
            (cfg.PortalScriptPath, null),
            ("--profile", "lowlatency"),
            ("--port", cfg.SourcePort.ToString()),
            ("--fps", cfg.CaptureFps),
            ("--bitrate-kbps", cfg.CaptureBitrateKbps),
            ("--size", cfg.CaptureSize),
            ("--cursor-mode", cfg.CursorMode),
            ("--debug-dir", "/dev/shm/proto-portal-frames"),
            ("--debug-fps", cfg.CaptureFps),
        };
        var environmentPairs = new (string Key, string Value)[]
        {
            ("PYTHONUNBUFFERED", "1"),
            ("WBEAM_FRAMED", cfg.SourceFramed ? "1" : "0"),
        };
        psi.AddArgumentPairs(argumentPairs);
        psi.SetEnvironmentPairs(environmentPairs);

        var proc = Process.Start(psi);
        if (proc is null)
        {
            ProgramLog("failed to start portal helper process");
            return null;
        }

        ProgramLog(
            $"portal helper started pid={proc.Id} framed={cfg.SourceFramed} source=tcp://127.0.0.1:{cfg.SourcePort}");
        return proc;
    }

    private static void ProgramLog(string msg)
    {
        Console.WriteLine($"[proto-cs] {msg}");
    }
}

internal static class ProcessStartInfoExtensions
{
    public static void AddArgumentPairs(
        this ProcessStartInfo psi,
        params (string Key, string? Value)[] args)
    {
        foreach (var (key, value) in args)
        {
            if (string.IsNullOrWhiteSpace(key))
            {
                continue;
            }

            psi.ArgumentList.Add(key);
            if (value is not null)
            {
                psi.ArgumentList.Add(value);
            }
        }
    }

    public static void SetEnvironmentPairs(
        this ProcessStartInfo psi,
        params (string Key, string Value)[] vars)
    {
        foreach (var (key, value) in vars)
        {
            if (string.IsNullOrWhiteSpace(key))
            {
                continue;
            }
            psi.Environment[key] = value;
        }
    }
}
