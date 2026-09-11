# Po0 firewall whitelist

Open **Po0 firewall whitelist** in the navigation drawer. In the Po0 console,
open the machine's firewall page and copy its add script. Paste only its
`pgnfw_…` token into ArcaenBox. Separate multiple machines with commas.

- **Save settings** stores the tokens and automatic-update choice. With automatic
  updates off, saving does not contact Po0.
- **Save and whitelist now** saves and submits the current physical connection's
  IPv4 /24 network to each configured machine.
- **Automatic whitelisting** reacts to network changes while the application or
  VPN process is alive. WorkManager provides a roughly 15-minute fallback after
  the process exits. Android Doze, force-stop and vendor battery restrictions can
  delay or suspend jobs; this is not a guaranteed timer.
- Optional `@0` through `@4` suffixes request fixed slots. A slot update can replace
  its previous network. Use separate slots per device and remove conflicting old
  slots in the Po0 console. Phones normally use ordinary entries without a suffix.
- Clear the tokens, switch automatic updates off, and save to remove the setup.

## Request and storage behavior

The protocol follows the [linked tutorial](https://wiki.uuuz.de/guide/tutorials/po0fw-whitelist.html)
and its [upstream implementation](https://github.com/w0ven/po0fw):
`POST https://124.221.69.228/api/firewall/<token>/add[?slot=N]`, empty body.
The IPv4 endpoint presents a publicly trusted IP certificate. Normal certificate
and hostname verification remain enabled. Requests cannot redirect, use a system
HTTP proxy, or fall back to the VPN: sockets and DNS bind to an explicitly checked
non-VPN Android `Network`.

When Po0 tokens are configured, ArcaenBox also adds the equivalent of
`IP-CIDR,124.221.69.228/32,DIRECT,no-resolve` to the beginning of the generated
sing-box route list. It uses a plain direct outbound and an IP-only matcher,
without introducing DNS resolution or changing DNS rules. This applies after
custom configuration merging and also covers full custom sing-box profiles.
Equivalent duplicate rules are coalesced; other rules retain their order.
The generated rule is managed automatically and is not added to the editable
user-rule database. Adding the first token or clearing all tokens reloads an
already connected proxy once to apply/remove it. Later updates do not reload it.
The worker validates the physical network itself because WorkManager's default
network validation can report an unusable VPN while the physical connection works.

Success requires an enabled firewall and the current exit in the returned
whitelist. A requested fixed slot must also match. Malformed responses, disabled
firewalls, slot conflicts, HTTP errors, TLS failures and missing networks appear
separately. Only transient failures retry, with bounded backoff. Server error text,
request URLs and token values never enter result messages or logs.

Tokens are encrypted with AES-GCM, whose per-save key is wrapped by a dedicated
Android Keystore RSA key (also compatible with Android 5). State uses an atomic
file and a cross-process lock under `noBackupFilesDir`; it is excluded from Android
backup and ArcaenBox configuration exports. Tokens are masked in the UI and are
not saved into activity-instance state or autofill.

## Artwork and promotion removal

The original supplied illustration was cut out locally, retaining its original
RGB pixels. The PNG has a real alpha channel. The launcher uses a standard bitmap,
because Android's AdaptiveIconDrawable paints black behind transparent layers.
The drawer and About page display the PNG directly. Some launchers still add their
own icon plate or theme; those launcher settings are outside the application's control.
The promotion menu, donation action, translated donation text and README donation
list were removed. Open-source credits and license notices remain.

## Verification

The release workflow runs protocol unit tests, verifies signed APK metadata and
the decoded transparent PNG in every architecture, and performs Android 15 UI
checks. Po0 checks cover required/invalid tokens, encrypted save-and-reopen,
password masking, removal of saved tokens, and light/dark layouts. Test tokens are
saved with automatic requests off and then removed; no real Po0 firewall is
modified by these checks. A real server/token is needed to validate successful
whitelisting over a device's actual Wi-Fi and mobile networks.
