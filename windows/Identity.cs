using System.IO;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.WebUtilities;

namespace PocketPlayer;

internal sealed class Identity : IDisposable
{
    private static readonly TimeSpan PairingWindow = TimeSpan.FromMinutes(3);
    private readonly object gate = new();
    private readonly string directory;
    private readonly string preferencesFile;
    private readonly TimeProvider clock;
    private Preferences preferences;
    private string? pairCode;
    private DateTimeOffset expires;
    private PairAttempt? attempt;

    public Identity(string directory) : this(directory, TimeProvider.System) { }

    internal Identity(string directory, TimeProvider clock)
    {
        this.directory = directory;
        this.clock = clock;
        Directory.CreateDirectory(directory);
        preferencesFile = Path.Combine(directory, "preferences.json");
        preferences = File.Exists(preferencesFile)
            ? JsonSerializer.Deserialize<Preferences>(File.ReadAllText(preferencesFile)) ?? new()
            : new();
        Certificate = LoadCertificate();
        Fingerprint = Convert.ToHexString(SHA256.HashData(Certificate.RawData)).ToLowerInvariant();
    }

    public X509Certificate2 Certificate { get; }
    public string Fingerprint { get; }
    public string ServiceName => "pp-" + Fingerprint[..16];
    public bool Paired { get { lock (gate) return preferences.TokenHash is not null; } }
    public bool Pairing { get { lock (gate) return pairCode is not null && clock.GetUtcNow() < expires; } }
    public string Theme { get { lock (gate) return preferences.Theme; } }
    public string Accent { get { lock (gate) return preferences.Accent; } }
    public bool StartWithPhone { get { lock (gate) return preferences.StartWithPhone; } }
    public string PhoneName { get { lock (gate) return preferences.PhoneName; } }
    public string? PairKey { get { lock (gate) return preferences.TokenHash; } }
    public PairAttempt? PendingPair { get { lock (gate) return Pairing ? attempt : null; } }

    public string OpenPairing(IReadOnlyList<string> addresses, int port)
    {
        lock (gate)
        {
            CancelPairing();
            pairCode = RandomSecret(24);
            expires = clock.GetUtcNow() + PairingWindow;
            attempt = null;
            Diagnostics.Record("pairing", "invitation_opened");
            return QueryHelpers.AddQueryString("pocketplayer://pair", new Dictionary<string, string?>
            {
                ["v"] = "2",
                ["key"] = Fingerprint,
                ["code"] = pairCode,
                ["ip"] = string.Join(",", addresses),
                ["port"] = port.ToString(System.Globalization.CultureInfo.InvariantCulture),
            });
        }
    }

    public void ClosePairing()
    {
        lock (gate) { pairCode = null; attempt = null; }
    }

    public void CancelPairing()
    {
        lock (gate)
        {
            try
            {
                if (attempt is { Status: "approved" } && preferences.CandidateHash is not null)
                    Save(preferences with { CandidateHash = null, CandidateName = "", CandidateExpires = default });
            }
            finally { ClosePairing(); }
        }
    }

    public PairAttempt? RequestPair(string? code, string? id, string? device)
    {
        lock (gate)
        {
            if (!Pairing || !Matches(pairCode!, code) || id is not { Length: 32 } || !id.All(Uri.IsHexDigit)) return null;
            if (attempt is null)
            {
                attempt = new(id, CleanDeviceName(device),
                    RandomNumberGenerator.GetInt32(1_000_000).ToString("D6", System.Globalization.CultureInfo.InvariantCulture),
                    "pending", null);
                expires = clock.GetUtcNow() + PairingWindow;
                Diagnostics.Record("pairing", "verification_started");
            }
            return Matches(attempt.Id, id) ? attempt : null;
        }
    }

    public PairAttempt? PairResult(string? id)
    {
        lock (gate) return Pairing && attempt is not null && Matches(attempt.Id, id) ? attempt : null;
    }

    public void DecidePair(string id, bool approve)
    {
        lock (gate)
        {
            if (PairResult(id) is not { Status: "pending" } pending) return;
            if (!approve)
            {
                attempt = pending with { Status = "denied" };
                Diagnostics.Record("pairing", "denied");
                return;
            }

            var token = RandomSecret(32);
            var now = clock.GetUtcNow();
            Save(preferences with { CandidateHash = Hash(token), CandidateName = pending.Device,
                CandidateExpires = now.AddMinutes(5) });
            attempt = pending with { Status = "approved", Token = token };
            expires = now + PairingWindow;
            Diagnostics.Record("pairing", "approved");
        }
    }

    public bool Authenticate(string? token)
    {
        lock (gate)
        {
            if (token is not { Length: 43 }) return false;
            string hash = Hash(token);
            if (preferences.TokenHash is string active && Matches(active, hash)) return true;
            if (preferences.CandidateHash is not string candidate || clock.GetUtcNow() >= preferences.CandidateExpires ||
                !Matches(candidate, hash)) return false;
            Save(preferences with { TokenHash = hash, PhoneName = preferences.CandidateName, CandidateHash = null,
                CandidateName = "", CandidateExpires = default });
            ClosePairing();
            Diagnostics.Record("pairing", "activated");
            return true;
        }
    }

    public string? RegisterPhone(string? token, string? device)
    {
        lock (gate)
        {
            if (!Authenticate(token)) return null;
            var name = CleanDeviceName(device);
            if (name != preferences.PhoneName) Save(preferences with { PhoneName = name });
            return preferences.TokenHash;
        }
    }

    public void SetStartWithPhone(bool enabled)
    {
        lock (gate) Save(preferences with { StartWithPhone = enabled });
    }

    private static string CleanDeviceName(string? value)
    {
        var clean = new string((value ?? "").Where(ch => !char.IsControl(ch) &&
            char.GetUnicodeCategory(ch) != System.Globalization.UnicodeCategory.Format).Take(64).ToArray()).Trim();
        return clean.Length > 0 ? clean : "Android";
    }

    public bool SetTheme(string theme, string? token = null)
    {
        if (theme is not ("light" or "dark" or "system")) return false;
        lock (gate)
        {
            if (token is not null && !Authenticate(token)) return false;
            if (preferences.Theme != theme) Save(preferences with { Theme = theme });
            return true;
        }
    }

    public bool SetAccent(string accent, string? token = null)
    {
        if (accent is not ("green" or "blue" or "violet" or "amber"))
        {
            if (accent is not { Length: 7 } || accent[0] != '#' || !accent.Skip(1).All(Uri.IsHexDigit)) return false;
            accent = accent.ToUpperInvariant();
        }
        lock (gate)
        {
            if (token is not null && !Authenticate(token)) return false;
            if (preferences.Accent != accent) Save(preferences with { Accent = accent });
            return true;
        }
    }

    public bool Forget(string? token = null, string? expectedPairKey = null)
    {
        lock (gate)
        {
            if ((token is not null && !Authenticate(token)) ||
                (expectedPairKey is not null && preferences.TokenHash != expectedPairKey)) return false;
            Save(preferences with { TokenHash = null, PhoneName = "", CandidateHash = null, CandidateName = "",
                CandidateExpires = default });
            ClosePairing();
            Diagnostics.Record("pairing", "revoked");
            return true;
        }
    }

    private void Save(Preferences value)
    {
        var temporary = preferencesFile + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(value));
        File.Move(temporary, preferencesFile, overwrite: true);
        preferences = value;
    }

    private X509Certificate2 LoadCertificate()
    {
        var path = Path.Combine(directory, "identity.bin");
        if (File.Exists(path))
        {
            var pfx = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
            try { return X509CertificateLoader.LoadPkcs12(pfx, "", X509KeyStorageFlags.UserKeySet); }
            finally { CryptographicOperations.ZeroMemory(pfx); }
        }

        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=Pocket Player", key, HashAlgorithmName.SHA256);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1") }, false));
        using var generated = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        var bytes = generated.Export(X509ContentType.Pfx, "");
        try
        {
            var temporary = path + ".tmp";
            File.WriteAllBytes(temporary, ProtectedData.Protect(bytes, null, DataProtectionScope.CurrentUser));
            File.Move(temporary, path);
            return X509CertificateLoader.LoadPkcs12(bytes, "", X509KeyStorageFlags.UserKeySet);
        }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }

    public void Dispose() => Certificate.Dispose();

    private static string RandomSecret(int bytes) => WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(bytes));
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    private static bool Matches(string expected, string? received) => received is not null && expected.Length == received.Length &&
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(received));

    internal sealed record PairAttempt(string Id, string Device, string Check, string Status, string? Token);
    private sealed record Preferences(string Theme = "system", string? TokenHash = null, string Accent = "green",
        string PhoneName = "", string? CandidateHash = null, string CandidateName = "", DateTimeOffset CandidateExpires = default,
        bool StartWithPhone = false);
}
