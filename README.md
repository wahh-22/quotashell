# QuotaShell

Native Kotlin Android dashboard and launcher widget (`dev.wahh.quotashell`, Android 8+).
Dark monospace panels and a single PhantomShell-style board showing OpenAI/Codex,
Claude and OpenCode Go together in proportional columns with compact cyberpunk charts.
Import a PhantomShell theme or edit its widget colors, shapes, typography and periodic glitch.

Direct HTTPS quota reads support manually entered Codex OAuth access tokens, Claude
OAuth access tokens with usage/profile scope, and OpenCode Go API keys. Credentials
are encrypted on-device using Android Keystore AES-GCM. **No official login, automatic
token renewal, cookie import, reset redemption or simulated quotas.** New installs
show **Disconnected / No snapshot**; only tests contain sample quota data.

## Connect on your device

Never send tokens, account IDs, keys, credential files or screenshots of them in chat.
Install the APK and open **Connections / reconnect**. Enter only credentials you own
and are permitted to use; these credentials can grant broad account access.

| Provider | Manual entry | Reported subscription windows |
| --- | --- | --- |
| OpenAI · Codex | Existing Codex OAuth **access token**, not an OpenAI API key or refresh token. Optionally enter the exact ChatGPT account/workspace ID to scope the request. Without it the provider chooses the token's default account. | Actual `limit_window_seconds` from primary/secondary windows; 5h, 7d and 30d are recognized, other durations remain explicitly reported. No inferred monthly allowance. |
| Claude | Existing Claude OAuth **access token** with `user:profile` scope, obtained through your authorized provider sign-in workflow. Inference-only/`claude setup-token` credentials, ordinary API keys and MCP tokens are not usage credentials. | `five_hour`, `seven_day` when supplied. `extra_usage` monthly spending is **not** subscription quota. |
| OpenCode Go | Provider-issued API key from your OpenCode account/key-management UI. | API `usage.rolling`, `weekly`, `monthly` when reported; percent is already 0–100. |

Choose **Save / replace and refresh**. Fields are masked, never prefilled, and cleared
when leaving the screen; enter token values only, without `Bearer`, quotes, spaces or
control characters. Codex account ID is masked too. Renew credentials through their
owner/provider, then replace them here. There is no credential-file discovery or
OAuth client impersonation. **Claude cookies are intentionally unsupported**; no
organization is silently selected. A 403 may indicate insufficient scope or permission,
not expiry. Unsupported third-party access may fail even with a working official client.

**Remove connection and cached quotas** deletes that provider's encrypted credential,
clears its quota/reset snapshot and reconnect notification, and invalidates in-flight
results. Replacing credentials also clears the old account's quota/notification state.
If you replace while a refresh is already running, wait for it to finish and tap
**Refresh existing connections**; old responses cannot repopulate the cache.

If credential persistence fails, the provider instead shows **Error · Snapshot storage**
with no quota/reset readings, and its reconnect notification is cleared. That connection
is quarantined from fetching/publication until a save or removal succeeds during this
process. A failure is not successful replacement/removal or proof of rollback: Android
can change the in-memory credential before reporting a failed disk write. Retry in
Connections. Disk persistence and the credential/cache reloaded after restart are
unconfirmed; do not rely on a failed removal as a durable disconnect. This quarantine
is process-local, not an atomic cross-file storage transaction or a restart guarantee.

### Reconnect alerts and refresh timing

A successful usage refresh clears the reconnect episode. HTTP **401** means
**credentials expired or rejected**, not proof of an exact expiration time. App and
widget show **Reconnect required**; tap the widget state to open Connections.
403/429/5xx/network/invalid-response failures remain separate errors and retain old
readings with their original age. A failed Codex reset-inventory request does not
invalidate successful usage; its count becomes unknown with a separate failure label.
Reset inventory is the server's `available_count`, never spending `credits.balance`.

Notifications are **on by default**; turn them off with the checkbox in Connections
(that explicit choice is kept). Installs from earlier default-off builds are switched
on once. On Android 13+ the dashboard asks for `POST_NOTIFICATIONS` once on first
open; Android 8+ uses the Credential reconnection channel. Denial/channel blocking leaves
the app/widget warning intact. One notification is posted per reconnect episode;
success, replacement or removal resets it. Notification taps open Connections without
credentials or account IDs. Lock-screen notification detail is private.

Native JobScheduler requests network refresh roughly every **30 minutes**, persists
across reboot, and uses the same gateway/store path as **Refresh now**. Android Doze,
force-stop, network and battery restrictions can delay checks. Expiry timestamps are
unknown: detection happens **only during refresh**, not at exact expiry or in real time.
No unsigned JWT claims are trusted and no refresh tokens are retained.

### Security and API caveats

- Credentials and optional account ID live only in separate app-private encrypted
  preferences; the nonexportable AES-256 key is generated by Android Keystore.
  GCM uses a fresh IV and binds ciphertext to its provider. Decryption/storage errors
  fail closed; they are not relabeled as credential expiry. Clear/re-enter a broken
  connection. Reinstall/clearing app data loses keys and credentials.
- Snapshots contain only sanitized quota windows, timestamps, counts and failure enums;
  no raw response, identifier, credential, intent extra or provider error is persisted
  there. All backup/device-transfer domains remain excluded. Connection input disables
  saved view state, autofill, screenshots and personalized keyboard learning. A trusted
  keyboard/device is still required; Java/Kotlin strings cannot promise memory zeroization,
  and Keystore does not protect against a compromised running app/device.
- Requests are GET-only to fixed HTTPS endpoints using **QuotaShell/0.1** identity and
  platform TLS trust. All redirects and response caching are disabled; connection/read
  timeouts are 10s, body-loop budget 25s (a blocking read may add up to 10s), and responses
  are capped at 256 KiB. No error body is read, no provider exception is logged, and no
  secret is echoed in UI errors. Unknown additive response fields are ignored, not cached;
  absent windows/values remain absent/unknown, zero remains zero, invalid values fail closed.
- These are observed upstream integrations, **not a promise of stable/supported public
  subscription APIs**. No live credential/account test has been performed. Only Codex
  reset **inventory count** is shown; individual credit IDs/expiry details and redemption
  are not implemented. Counts are observations, not proof they remain redeemable later.

Evidence inspected selectively: upstream [Codex](https://github.com/steipete/CodexBar/blob/main/docs/codex.md),
[Claude](https://github.com/steipete/CodexBar/blob/main/docs/claude.md),
[OpenCode Go](https://github.com/steipete/CodexBar/blob/main/docs/opencodego.md),
[OpenCode](https://github.com/steipete/CodexBar/blob/main/docs/opencode.md),
[Go fetcher](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/OpenCodeGo/OpenCodeGoUsageFetcher.swift),
[reset-field parser](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Shared/OpenCodeWebParsing.swift),
and [Codex OAuth fetcher](https://github.com/steipete/CodexBar/blob/main/Sources/CodexBarCore/Providers/Codex/CodexOAuth/CodexOAuthUsageFetcher.swift).
Codex uses `/backend-api/wham/usage` and separate `/rate-limit-reset-credits` on
`chatgpt.com` with Bearer/optional `ChatGPT-Account-Id`. Claude uses
`https://api.anthropic.com/api/oauth/usage?cedar_ember=1` plus
`anthropic-beta: oauth-2025-04-20`. Go uses `https://opencode.ai/zen/go/v1/usage`.
Go relative resets use observed `resetInSec` aliases; absolute `resetAt`/`resetsAt`/
`reset_at`/`resets_at` aliases are supported with epoch seconds, epoch milliseconds
(as recognized by upstream), or ISO-8601 values.

## Build

Requires Java 17, SDK Platform 35 and Build Tools 34.0.0. Gradle 8.9 is pinned;
AGP 8.7.3 and Kotlin 2.0.21 use Google/Maven Central. No Compose/AndroidX runtime.
The official wrapper JAR was generated with the checksum-verified Gradle distribution
and independently matched the official wrapper checksum:
`498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.

Keep tooling local to this checkout:

```sh
export GRADLE_USER_HOME="$PWD/.gradle"
export ANDROID_USER_HOME="$PWD/.tools/android-user"
export ANDROID_HOME="$PWD/.tools/android-sdk"
mkdir -p "$ANDROID_USER_HOME"
./gradlew :core:test --console=plain
```

Core tests need no SDK. To build the APK, first have the SDK owner **review and
accept** the Android SDK licenses for this local root, then install the packages:

```sh
# Interactive: do not pipe automatic license acceptance.
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;34.0.0'
./gradlew :app:assembleDebug :app:lintDebug --console=plain
python3 app/checks/check_backup_policy.py
```

Alternatively supply an explicitly authorized SDK using `ANDROID_HOME` or an
ignored `local.properties` (`sdk.dir=/authorized/sdk`). Automatic SDK download is
disabled. Debug signing material stays in ignored `app/build/`, not the home directory.
The build generates a development-only key there with the JDK's `keytool`; subsequent
builds reuse it. Do not distribute this debug key or use it for release signing.
APK output: `app/build/outputs/apk/debug/app-debug.apk`. No release/publishing setup.

### Foundation validation (before provider integration)

- Java 17.0.11. With explicit user authorization, SDK licenses were accepted **only**
  for `.tools/android-sdk`. Platform 35 revision 2 and Build Tools 34.0.0 are installed;
  both SDK inventory and nonempty `android.jar`/build-tool files were verified.
- Nine JVM tests passed on a forced rerun, including null/zero preservation, quota
  states, theme behavior and damaged-date regression. Original RED logs are retained.
- APK assembly **passed**. The first SDK-enabled build failed at signing validation
  because a relocated debug key is not auto-generated by AGP. A project-local
  generation task fixed this; rebuild and APK signature verification passed.
- Android lint passed with **0 errors, 5 warnings**: four newer widget attributes
  intentionally ignored on older APIs (dimension/initial-layout fallbacks remain),
  and one possible-overdraw inference for the launcher-hosted RemoteViews layout.
  No lint baseline or suppression was added. Four compiler deprecation warnings for
  legacy-compatible window-inset accessors also remain.
- One SDK-independent backup-policy regression passed after observed RED failures.
  Legacy backup is explicitly disabled; Android 12+ cloud/device transfer rules
  exclude every app-data domain. This validates configuration, not a device transfer.
- No emulator/device/PhantomShell check yet. Platform-tools are not installed in
  this local SDK; Gradle warns but APK assembly/lint do not require them.

Exact validation commands use the three environment exports above. All logs below
are combined stdout/stderr under ignored `.build-evidence/`:

| Command | Exit | Log |
| --- | --- | --- |
| `yes \| sdkmanager --sdk_root="$ANDROID_HOME" --licenses` (explicitly authorized) | 0 (sdkmanager) | `resume-sdk-licenses.log` |
| `sdkmanager --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;34.0.0'` | 0 | `resume-sdk-install.log` |
| `sdkmanager --sdk_root="$ANDROID_HOME" --list_installed` | 0 | `resume-sdk-inventory.log` |
| `./gradlew :core:test --rerun-tasks --console=plain` | 0 | `resume-core-tests.log` |
| `./gradlew :app:assembleDebug --console=plain` (first SDK-enabled build) | 1: missing local debug key | `resume-assemble-first.log` |
| `./gradlew :app:lintDebug --console=plain` (initial / after resource fixes) | 0 / 0 | `resume-lint-first.log` / `resume-lint-final.log` |
| `python3 app/checks/check_backup_policy.py` (modern RED / legacy RED / final GREEN) | 1 / 1 / 0 | `resume-backup-red.log` / `resume-legacy-backup-red.log` / `resume-backup-final.log` |
| `./gradlew :core:test :app:assembleDebug :app:lintDebug --rerun-tasks --console=plain` | 0: nine JVM tests, APK and lint passed | `resume-final-validation.log` |
| `.tools/android-sdk/build-tools/34.0.0/apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk` | 0: verifies, v2 signature | `resume-apk-signature.log` |

Lint reports: `app/build/reports/lint-results-debug.{txt,html,xml}`. Initial project
previous-command differential baseline: **N/A**. No tests were omitted or ignored.

## Dashboard and widget

1. Install the debug APK after a successful build. Open QuotaShell → Settings.
2. Import or edit a theme (see below), and set a stale threshold (30–1440 min).
3. In PhantomShell or another widget-capable launcher, add **QuotaShell · all providers**
   and Save; Cancel does not add the widget. The default footprint is about 4×2 cells;
   resize for readability. All three providers always appear side by side, in Codex,
   Claude, OpenCode Go order; existing instances retain their launcher-assigned size.
   Each window places its chart on the **left** and a larger used percent, window label,
   local reset date and time on the **right**. CELLS bars span the available card height.
   Column widths follow chart-plus-text measurements; spare width follows chart counts,
   so Codex does not reserve the same space as a provider with three windows. The whole
   board scales up as well as down to fit both host dimensions (enlargement capped at
   1.8×). Codex reset inventory and reconnect/error/stale indicators remain visible.
   Sync sits beside the title, uses the theme's button shape, and shares pixel-aligned
   bounds with its invisible touch target (also in RTL device locales).
   For the default natural-case 1/2/3-window fixture at 405×248 dp, density 2.25,
   tighter card spacing increases content scale from **1.216947× to 1.463150×**;
   Sync measures **96.444×49.778 dp** (previous logical button height: 14×scale).
   These are bundled-font/renderer-metric calculations, not screenshot measurements;
   window count, labels, font offset, theme and widget size affect actual scale.
4. Choose **Quota chart style** in Settings: **CELLS** (default) uses narrow vertical
   segmented power cells, **TICKS** uses a 12-segment HUD arc, and **RING** keeps the donut.
   Unknown usage has empty segments/track and **N/A**; 85%+ uses the notifications color.
5. Tap the board for the dashboard or **[ Sync ]** to refresh directly in the widget,
   without opening an activity. **Syncing…** remains visible while the network-constrained
   background job is queued/running; repeated taps do not enqueue duplicate jobs.
   Completion, failure or job cancellation clears it. Android network/battery restrictions
   can delay it. Concurrent dashboard/periodic refreshes share the existing guarded fetch.
   Open Settings from the dashboard to reconfigure all widgets together.

Settings/data saves update all widgets immediately. Each provider shows an absolute
local **Sync HH:mm** time, on both the widget and dashboard; reconnect/error/stale
state words stay visible. Unknown timestamps remain **No snapshot**. The footer keeps
**Last sync HH:mm** for the newest provider snapshot. These timestamps do not drift
into a frozen “just now” label, and failed refreshes retain the last successful time.
The dashboard reevaluates stale state every minute while visible; widgets request a
local re-render every 30 minutes, subject to Android/launcher battery policy.
Background provider sync is **about every 30 minutes**, deferrable by Android's Doze,
network and battery restrictions; manual Sync/Refresh now also use the same gateway.
Stale transitions can lag on a sleeping launcher; this is not a real-time expiry alarm.
Launcher sizing/font scaling must be verified on-device.

## App HUD

The app uses a **nu11signal-inspired terminal instrument panel**, not widget cards:
Kode Mono, dense one-cell angular frames, `//` metadata, `◢◤◢◤` hazard marks,
bracketed controls and native bracketed/underlined input fields. Dashboard,
Connections and Appearance share a HUD wordmark, per-provider AUTH/LINK state,
a local digital clock and NODE QSH-01 code. SIG reflects available fresh provider
links (0–3), **not physical radio strength**. AUTH describes the last quota
observation; it is not an independent token-validity probe.

Provider panels show LINK OK / RECONNECT / NO CARRIER / STALE / SYNCING without
hiding detailed failure labels. Dashboard quota rows have horizontal filled/empty
terminal segments on the left, usage and local reset times on the right, absolute
SYNC HH:mm timestamps and separate Codex reset inventory. Unknown usage is not zero.
The widget keeps its existing CELLS/TICKS/RING styling and **Adwaita Mono**.
App frames stay angular and controls bracketed regardless of widget shape selection;
widget shape/chart settings and their import/export semantics are unchanged.

Colors come exclusively from the saved PhantomShell roles: outline for frames,
subtext for muted metadata, subtext2 for purple-style accents, notifications for
cyan-style highlights, text/accent for readings, window/background for surfaces.
Custom themes may choose different hues. Font offset and label all-caps still apply;
metadata/control labels are explicitly uppercase. A shared 16 dp outer gutter,
48 dp input/control touch heights, real native labels/buttons, chart descriptions
and unchanged accessibility text remain. System bars follow the theme background;
Android owns document/permission pickers and the keyboard.

Theme listeners restyle existing controls without rebuilding credential drafts.
Import/reset edits only a draft until Save. Connections retains FLAG_SECURE,
masked non-prefilled fields, pause clearing, autofill/state/keyboard-learning
protections and the existing save/remove/notification flows. No input value is
read by presentation effects. Native EditText remains responsible for editing.

### Motion and calm mode

On opening a screen or changing visible values, decode reveal uses the shared
`decodeReveal` rule: **900 ms at 20 fps**, about half of changed non-space cells,
`A-Z0-9+-=:/<>`, left-to-right resolution with
`0.2 + 0.7*(1-sqrt(1-columnFraction)) + 0.1*random`. Unchanged cells stay clean.
Detached native labels render the reveal; actual labels/accessibility text and
button hit areas never change. The clock and native toggles stay clean.

While focused and visible, an occasional signal-loss burst waits **10–22 s**,
then lasts **600–1000 ms at approximately 15 fps**. One selected visible text
surface uses the shared `signalLossPlan`: 1–3 row shifts by 1–2 cells, a one-third
static-row chance and 4–10 corrupted cells (capped on tiny surfaces) from
`░▒▓█▚▞0137ACEF`. No signal message, RGB split, background scanlines or moving
hit targets. Captures/allocations happen outside `onDraw`; originals restore on
completion, focus loss, detachment or animation disable. No parent or EditText is
captured. Overlays neither intercept input nor expose duplicate TalkBack nodes.

Both effects require **notificationAnimation=GLITCH** and nonzero system animator
scale. Choose **Notification animation → NONE** and Save, or Android Remove
animations, for the clean/calm path. Focus loss cancels scheduled bursts and frames;
there are no network requests or background services for app motion. The clock
updates once per second while focused; rendering/motion has a modest battery cost.

### Bundled Kode Mono

Official Google Fonts source pinned to revision
`909667d11fe41a8384294d064950c48c53904d27`:
[font](https://raw.githubusercontent.com/google/fonts/909667d11fe41a8384294d064950c48c53904d27/ofl/kodemono/KodeMono%5BROND%2Cwdth%2Cwght%5D.ttf),
[SIL OFL 1.1 license](https://raw.githubusercontent.com/google/fonts/909667d11fe41a8384294d064950c48c53904d27/ofl/kodemono/OFL.txt).
The font's embedded name-table version is **2.002**. Bundled unchanged in
`app/src/main/assets/fonts/KodeMono[ROND,wdth,wght].ttf`; Android API 26+
Typeface.Builder selects weight 400/700, width 100 and roundness 0 for all app
labels, buttons, inputs and Spinner items. License: `KodeMono-OFL.txt` beside it.

SHA-256:
- Font: `bd7b11b37524e03e270c7a1726f08cc52cb17e8ebc3f664f9d323dfd9277b311`
- License: `56de2b11bf783431f18a9ada94a2ff20fafaab49fe8b0b1f63e29df9187957b1`

Validation: HUD status/freshness priority, link indicator bounds, local clock and
burst schedule JVM tests; existing signal-loss/decode tests; source guards in
`python3 -I app/checks/check_app_style.py`. These are not runtime security,
visual-fidelity or frame-rate proof. Device font fallback, foldable/large-font
layout, touch, TalkBack and custom low-contrast themes still need physical checks.
No screenshots were taken for this redesign.

## PhantomShell theme import and export

Open **Settings / widget appearance → Import PhantomShell theme**, then select a
PhantomShell JSON export using Android's document picker. Files must be UTF-8 JSON
objects, at most **64 KiB**, with all eight widget colors and the five presentation
options. Invalid HSL ranges, non-finite numbers, wrong types and malformed JSON show
an error without replacing the draft or stored theme. Reading/parsing and writing run
off the UI thread. If the screen is recreated while an operation is running, retry it.

Import and **Reset to default** load a draft: **Save** persists it and immediately
requests updates for every widget through the normal snapshot-store change path.
Cancel leaves the stored theme unchanged. **Export theme** writes the current valid
draft through Android's create-document picker; it does not implicitly Save.

The default is the supplied PhantomShell **blue.json**, not the previously sampled
palette. Edit each slot using six-digit **RRGGBB** and **alpha 0–100%**, select shapes,
toggle all-caps, or enter a font-size offset from **−4 to +6**. Imported offsets outside
that range are clamped only for rendering; small text has a 3-unit minimum. Larger text
adds row spacing, and the entire board still scales to fit the widget's bounds.

| Export setting | Widget behavior |
| --- | --- |
| `window` / `outline` | Panel fills / outlines and dividers; each uses its own alpha. |
| `text` / `subtext` / `subtext2` | Primary text / secondary text and labels / stripes, corner marks and codes. |
| `accent` / `notifications` | Chart segments/rings and Sync / alert glyphs, alert-card highlight and hot usage (85%+). |
| `background` | Outer frame fill, including independent transparency. |
| `windowShape` | `TABBED`: tab-labelled chamfered cards; `BEVEL`: chamfers without tabs; `SQUARED` and other values: rectangles. |
| `buttonShape` | `SQUARED`: bracketed Sync; `BEVEL`: chamfered outline. Other values fall back to brackets. |
| `notificationShape` | `INVERTED TRIANGLE`, `TRIANGLE`, `CIRCLE`, `SQUARE`, `NONE`. NONE hides the glyph, not the alert-card highlight. Unknown values use an inverted triangle. |
| `isAllCaps` / `fontSizeOffset` | Uppercase versus natural-case board text / a bounded step added to every text size. |
| `notificationAnimation` | `GLITCH`: periodic signal-loss frames plus decode reveal on changed content/new alerts. `FADE`, `DEFAULT`, `NONE` and unknown values disable both animations. Editable in Settings. |
| `quotaShellChartStyle` | QuotaShell-only `CELLS`, `TICKS`, `RING`; stored in nonsecret theme JSON and preserved on export. PhantomShell ignores this extra key. Missing/unknown values render as CELLS; unknown values survive an unchanged edit/export. |

Periodic glitch follows **nu11signal-style signal loss**, adapted to bitmap text-row
strips: each frame shifts **1–3 distinct horizontal rows by 1–2 character cells**,
clipping and padding from an immutable clean source instead of smearing copies.
One frame in three replaces a full row band with shaded static. **4–10 distinct cells**
use `░▒▓█▚▞0137ACEF`, colored from accent, notifications, subtext2 and outline roles.
Very tiny surfaces cap corruption at the number of available cells. Header/Sync,
provider status and footer rows stay clean. There is **no NO SIGNAL overlay**, doubled
corruption, RGB split, element jitter or CRT scanline effect; titles render clean
when settled. SplitMix64-style hashing combines seed, salt, frame and coordinates.

Changed content/new alerts instead use **decode reveal**: four frames 225 ms apart
scramble about half of changed, non-space text cells with
`ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+-=:/<>`, then settle at **900 ms**. Unchanged
cells remain untouched; resolution tends left-to-right with normalized resolve time
`0.2 + 0.7*(1-sqrt(1-columnFraction)) + 0.1*random`. Status/footer/Sync stay readable.
Timestamp-only updates and the first render after process start do not reveal.
Android may coalesce updates or end the process, so playback is best effort.

A launcher-driven **ViewFlipper** plays four loss frames at 200 ms, then returns to
the clean board: an **800 ms burst every 10.8–22 seconds**, seeded from board content.
The pre-rendered cycle repeats until the next widget update; it does not invent new
random frames or wake the app for each burst. Clean children reuse the **same Bitmap
instance** through RemoteViews' bitmap cache; the base ImageView is hidden while
flipping, keeping clean-frame theme transparency intact.
At 405×248 dp and density 2.25, each bitmap is 911×558 pixels (**2,033,352 bytes**);
one clean plus four loss frames use **10,166,760 bytes** (~9.70 MiB), below 12 MB and
the cover display's approximately 16.3 MB platform budget. Larger hosts reduce
bitmap density to cap each frame at 2 MiB: five frames stay below **10,485,760 bytes**.
Decode-reveal updates carry just one bitmap.
No app timer, wake lock, foreground service or network request is needed for periodic
playback. **Battery tradeoff:** while visible, the launcher redraws per flip even for
clean children; flipping pauses when not visible. Launcher support/playback is best
effort and the cycle restarts on widget updates.

To disable periodic glitch and bursts, choose **Notification animation → NONE** in the
theme editor and Save, or enable Android's **Remove animations** (animator duration scale
0; applied on the next widget render). The flipper is hidden and no glitch frames are
sent when disabled. The board stays clean. Unknown animation and shape strings
remain unchanged on export.
Other launcher-only keys (including button/window animations, toolkit, status bar,
keyboard, immersive mode, bottom text, username, blur and snap-to-grid) are retained
but do not affect the widget.
`vignette` can be edited and round-tripped but is not rendered; `vignetteAngle` is
also export-only. These options describe widget approximations, not a reproduction
of the launcher's internal layout engine or proof of its per-field enum mappings.

Exports preserve JSON field values, including unknown nested objects/arrays, where
practical, not original whitespace or numeric spelling. Numbers use the existing
strict parser's Double representation. Unchanged editor fields retain their original
HSL/fractional alpha rather than being quantized to the displayed hex/whole percent.
The saved theme also styles the native app (see App HUD above).

**Migration:** existing explicitly stored accent RGB becomes the theme's accent;
legacy panel opacity becomes only `window.alpha`. Other slots, including the outer
background alpha, take blue.json defaults. Missing legacy keys do not override defaults.
Saving writes the complete JSON to **nonsecret** preferences and removes the two old
appearance keys; credential storage is never involved. Reset + Save discards legacy
appearance overrides and restores the exact supplied default theme.

## T3 integration contracts

- **Domain:** `app/core/.../Quota.kt`. `ProviderSnapshot` holds a provider, explicit
  connection state, last successful `capturedAt`, optional windows, nullable
  `resetInventoryCount` and sanitized `Failure` enum. No secrets/account IDs/raw
  errors. Absence is not zero: omit unsupported windows; null percent/reset means
  unknown. Percent is **used**, finite 0–100; invalid input must become an error,
  not clamp into believable quota. Reset inventory is independent of quota/spending
  credits and is displayed only for Codex. All reset dates display in UTC.
- **Refresh:** install `QuotaApplication.refreshGateway` in its `onCreate` composition
  root. `RefreshGateway.refresh(current)` runs on one background executor. Return
  `RefreshResult.Updated(snapshots)`; omitted providers are retained. `NotConfigured`
  leaves data/timestamps untouched. UI/widget actions already call this path.
  Integrations must bound network timeouts and return unique providers. The installed
  provider gateway uses secure credentials and fixed HTTPS adapters. Main-thread
  per-provider generation checks precede the single snapshot write boundary, so old
  successes and failures cannot undo credential replacement/disconnection.
- **Failures:** preserve successful timestamps/windows on refresh failure. Map
  rejected/expired authentication to `RECONNECT_REQUIRED`; network, rate-limit,
  permission, malformed data and service failures to `ERROR` with the matching
  failure enum, not credential expiry. Generic unexpected exceptions are sanitized
  as service errors; provider exceptions are never logged. Secure connection controls
  and default-on reconnect notifications are implemented separately. Unknown credential expiry is
  detectable on refresh, not promised in real time.
- **Persistence:** `SnapshotStore.saveSnapshots()` is the single sanitized write
  boundary and triggers listeners plus all widget renders. Its versioned
  `SnapshotCodec` preserves null/zero and reports malformed cache as storage error.
  SharedPreferences contains **nonsecret** snapshots/theme/widget selection only;
  backup is disabled. `SecureCredentialStore` is separate and the manifest explicitly
  declares network/job/notification permissions. Never put credentials in
  this cache, intents, widget content, logs or test fixtures.
- **Rendering:** shared pure `present(snapshot, now, staleAfterSeconds)` drives both
  surfaces. Disconnected, reconnect, error and stale are explicit; reconnect/error
  take precedence over freshness while old readings retain their visible age.
  `CONNECTED` without a timestamp never claims fresh data.

## T3 validation and review handoff

Coverage: **S2** (all three direct adapters and connection controls), **S3** (reported
subscription windows and separate Codex reset inventory), **S7** (refresh-detected
reconnect state, optional permission-aware deduplicated notifications).

All Gradle commands below use these exact project-local environment assignments:

```sh
GRADLE_USER_HOME="$PWD/.gradle" ANDROID_USER_HOME="$PWD/.tools/android-user" ANDROID_HOME="$PWD/.tools/android-sdk" ./gradlew :core:test --console=plain
GRADLE_USER_HOME="$PWD/.gradle" ANDROID_USER_HOME="$PWD/.tools/android-user" ANDROID_HOME="$PWD/.tools/android-sdk" ./gradlew :core:test :app:assembleDebug :app:lintDebug --rerun-tasks --console=plain
python3 app/checks/check_backup_policy.py
```

Each evidence file retains combined stdout/stderr. No live credentials or accounts
were used. RED failures were observed before the corresponding implementation/fix:

| Command / stage | Exit | `.build-evidence/` log | Observed result |
| --- | --- | --- | --- |
| `:core:test --console=plain`, initial RED | 1 | `t3-red.log` | Missing provider APIs/extended fields at test compilation |
| Same, initial GREEN | 0 | `t3-green.log` | 19 tests passed |
| Same, transport RED | 1 | `t3-transport-red.log` | Missing bounded HTTPS transport |
| Same, cipher RED | 1 | `t3-cipher-red.log` | Missing credential cipher |
| Same, unknown-duration RED | 1 | `t3-triangulate-red.log` | 27 tests ran; two unknown Codex durations collided (one failing test); transport/cipher cases already passed |
| Same, triangulation GREEN | 0 | `t3-triangulate-green.log` | All 27 tests passed |
| Same, fractional-display RED / GREEN | 1 / 0 | `t3-fraction-red.log` / `t3-fraction-green.log` | 0.5% display was rounded; now preserves reported fractional percent |
| Same, unexpected-service-error RED / GREEN | 1 / 0 | `t3-service-red.log` / `t3-service-green.log` | Unexpected adapter exception now maps to service, not malformed data |
| `python3 app/checks/check_backup_policy.py`, RED / GREEN | 1 / 0 | `t3-policy-red.log` / `t3-policy-green.log` | Missing network-state job permission caught; both configuration tests pass after fix |
| Same core command, Go millisecond-reset RED / GREEN | 1 / 0 | `t3-go-millis-red.log` / `t3-go-millis-green.log` | Epoch milliseconds now retain the actual reset date, matching upstream date parsing |
| Full three-task command above, final forced rerun | 0 | `t3-delivery-validation.log` | 27 JVM tests, APK assembly and lint passed; 48 tasks executed |
| `python3 app/checks/check_backup_policy.py`, final | 0 | `t3-delivery-policy.log` | 2 tests passed: full backup exclusion and manifest integration boundary |

The 27 JVM tests include the **nine unchanged foundation regressions**, plus provider
fixtures, missing-versus-zero windows, actual duration/reset metadata, independent
reset-count failure, auth/network/permission/service mapping, fake-transport credential
mutation races, notification episode policy, strict JSON bounds, redirect/timeout/body
limits and AES-GCM round-trip/tamper/provider/key isolation. No tests are skipped.
The shared `SnapshotStore.saveSnapshots` and `RefreshGateway` contracts remain intact;
`SnapshotCodec` round-trips the additive duration/inventory-failure fields while reading
previous snapshots without them. **New-project baseline limitation:** the foundation
was untracked at task start; no committed previous-code executable/differential baseline
exists. Existing behavior tests and legacy-format fixtures are the regression baseline,
not a fixed Git-history dependency. No previous-code scratch execution is claimed.

APK: **`app/build/outputs/apk/debug/app-debug.apk`**. Lint:
`app/build/reports/lint-results-debug.{txt,html,xml}` — **0 errors, 5 pre-existing
warnings** (four newer widget attributes and inferred RemoteViews overdraw). Four
pre-existing inset-accessor compiler deprecations remain; no suppressions/baseline added.
The automatic Kotlin LSP intermittently reports unresolved newly added same-package
symbols in `ProviderTest.kt`, even after explicit typing/import checks; imports to
unrelated JDK classes were not applied. The actual Kotlin compiler and all final tests
pass. LSP workspace refresh is a tooling follow-up, not a claimed clean diagnostic run.
CodeGraph had no index; its initialization would write outside the allowed surfaces,
so scoped file inspection was used. Two guessed upstream source URLs returned 404;
exact files were then located from GitHub directory/path metadata and inspected.

Suggested coherent review slices (no commits made; do not omit tests to force size):
1. Domain, strict parsing, transport, provider fixtures, codec/presentation regressions.
2. Credential encryption/store, race publication guard, Connections UI and manifest.
3. Background scheduling, notification policy/widget navigation, backup regression and docs.

Remaining risk review: **item 2** — manual broad credentials and unsupported API
contracts need security review; **item 4** — credential/refresh concurrency, including
B1 below, needs the targeted verifier recheck. Review size is a separate delivery concern,
not item 4. Missing emulator, physical-device, PhantomShell, Keystore hardware,
reboot/Doze, notification-permission and live-provider proof are verification limitations,
not an assignment of risk item 5. Native platform wiring is build/lint/configuration-checked,
not instrumented on a device. Git/review closure remains with the parent. No commits,
publishing or parent task-log edits occurred.

## B1 bounded correction evidence

The initial generation fence ran only after `SecureCredentialStore.replace` returned.
A failed `SharedPreferences.commit()` can follow an in-memory credential change, so
throwing before invalidation allowed old 200/401 responses to publish. The narrow
`CredentialMutations` coordinator now fences **every attempt before writing**: advance
provider generation, block publication/fetch, clear notification and snapshot, then
attempt persistence. Failure publishes an empty `ERROR/STORAGE` snapshot and rethrows
a sanitized error; success unblocks the connection. Successful retry/removal still
clears account state and rejects all earlier generations. No credentials enter snapshots.

Production linkage: `QuotaApplication.replaceCredential` calls this coordinator with
`SecureCredentialStore::replace`, `SnapshotStore.saveSnapshots` and notification-clear
callbacks. The installed refresh gateway checks its quarantine before credential reads;
main-thread response publication uses its `accepts` barrier. The snapshot write boundary
and `RefreshGateway` result semantics are unchanged. No automatic retry or claimed
rollback masks a failed disk commit. A new process cannot infer durable credential state
from a prior process-local failure, and Android persistence/restart behavior has **not**
been runtime-tested. Retry until save/removal succeeds before trusting durable state.

The regression exercises this **production-used coordinator**, not a separate ordering
model. It was first extracted with the previous ordering and wired into the application;
the focused RED then observed four failed-commit interleavings and the pre-commit
ordering check failing. Fakes mutate credential memory and then return false, matching
the verifier's documented failure premise without claiming Android runtime execution.
Assertions cover generation advancement, snapshot/notification clearing, old success
and auth-response rejection, blocked subsequent publication, unaffected sibling providers,
and successful replacement/removal retry. Original 27 tests remain passing.

Exact commands (all output includes stdout and stderr in `.build-evidence/`):

```sh
GRADLE_USER_HOME="$PWD/.gradle" ANDROID_USER_HOME="$PWD/.tools/android-user" ANDROID_HOME="$PWD/.tools/android-sdk" ./gradlew :core:test --tests dev.wahh.quotashell.core.CredentialMutationsTest --console=plain
GRADLE_USER_HOME="$PWD/.gradle" ANDROID_USER_HOME="$PWD/.tools/android-user" ANDROID_HOME="$PWD/.tools/android-sdk" ./gradlew :core:test :app:assembleDebug :app:lintDebug --rerun-tasks --console=plain
python3 app/checks/check_backup_policy.py
```

| Command/stage | Exit | Log | Result |
| --- | --- | --- | --- |
| Focused RED | 1 | `b1-red.log` | Six tests executed; four failed-commit cases plus pre-commit ordering failed (five failures) |
| Focused GREEN | 0 | `b1-green.log` | All six coordinator regressions passed |
| Full forced validation | 0 | `b1-full-validation.log` | 33 tests, no failures/skips; APK assembled; lint 0 errors/5 existing warnings; 48 tasks executed |
| Backup/manifest check | 0 | `b1-backup-validation.log` | Two tests passed |

APK remains `app/build/outputs/apk/debug/app-debug.apk`. The four pre-existing inset
compiler deprecations remain. No real credentials, live-account checks, device tests,
new review sweep, commits or publishing were used for B1.

## Verification still needed

Install on API 26 and API 35+, and check widget add/cancel/reconfigure/resize/delete,
multiple provider instances, process restart, theme persistence, large font scale,
transparency against wallpaper, and immediate updates after real data saves.
No live provider or device-level credential-expiry behavior has been tested.

Official references: [Android widgets](https://developer.android.com/develop/ui/views/appwidgets),
[widget configuration](https://developer.android.com/develop/ui/views/appwidgets/configuration),
[AGP 8.7 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes),
[Gradle wrapper checksums](https://gradle.org/release-checksums/).
