# Codex Usage Monitor

A native Android home-screen monitor for your ChatGPT-backed Codex quotas. The small (2×2) widget shows the five-hour window. Medium (4×2) adds weekly usage. Large (4×4) adds the plan, reset dates, and other percentage-based windows supplied by Codex.

## Install on your phone

1. Open [Android APK builds](https://github.com/vishnu-sundar/android-widget-/actions/workflows/build-apk.yml) while signed in to GitHub. Choose a successful run and download the **codex-usage-debug** artifact.
2. Extract the ZIP, tap **app-debug.apk**, and allow installation from that browser or file manager if prompted. Android 8.0 or newer is required. For a USB installation, use `adb install -r app-debug.apk`.
3. Choose **Settings** in the app and pair your computer using the instructions below.
4. Long-press the home screen, choose **Widgets → Codex Usage**, and drag the desired size onto your launcher. Long-press it to resize. The widget opens the dashboard when tapped.

GitHub-hosted runners can generate different debug signing keys. If Android rejects an update because of a signature conflict, uninstall the previous debug build and pair again. Use a consistently signed release for long-term installations.

## Connect your Codex account

Use a trusted Mac or Linux computer with Python 3.9+ and the Codex CLI. Sign in with ChatGPT using `codex login` and check `codex login status`. Run the helper as that same OS user. The computer must be awake and reachable for live refreshes.

From a checkout of this repository:

```sh
python3 bridge/usage_bridge.py init
python3 bridge/usage_bridge.py serve
```

Init generates a random pairing secret in a user-only file. Serve listens only on 127.0.0.1:8765. In another terminal, configure private HTTPS with a trusted certificate. For example, with Tailscale installed on both devices, signed in to your tailnet, and HTTPS enabled:

```sh
tailscale serve --bg http://127.0.0.1:8765
tailscale serve status
```

Restrict tailnet access to your devices. Copy the private HTTPS origin from Serve into **Settings → HTTPS bridge URL**. On your own computer, open `~/.config/codex-usage-widget/bridge.token` privately, enter its contents in **Bridge token**, and tap **Connect bridge**. Keep this secret out of screenshots and logs. A private HTTPS reverse proxy with a phone-trusted certificate also works. Plain HTTP, redirects, URL credentials and query parameters are rejected by the Android client.

To disconnect, choose **Disconnect and clear data**. This clears phone-side credentials, readings, alerts and scheduled refresh. To revoke existing pairings on the host, stop the bridge, remove its token file, run init again, and restart the bridge. Host sign-in remains under your control.

### One-time offline reading

```sh
python3 bridge/usage_bridge.py export > usage.json
```

Transfer this usage-only file privately. Choose **Settings → Import usage snapshot**. Import clears any active pairing and labels the data as an imported snapshot. Repeat the export and import to update it. Do not import Codex auth files or session histories.

## Where the numbers come from

The helper opens a local Codex app-server over stdio, negotiates the official protocol, checks the account type using `account/read`, and reads `account/rateLimits/read`. It makes no inference requests. Only a generic account label, optional plan, window identifiers, percentages, timestamps and window lengths are sent to the phone. OpenAI authentication stays with the CLI. The helper's own authenticated `GET /usage` route is part of this project.

The documented RPC supplies single-bucket and multi-bucket responses. Window duration identifies 300-minute and 10,080-minute Codex limits. Additional reported windows (including code review or individual spend control when supplied) retain their own labels. Usage equals the source percentage. Remaining equals 100 minus usage. Missing or invalid values display as unavailable. API billing/organization spend is a separate metric. This project uses percentage-based quotas and does not convert credit balances into percentages.

[OpenAI Codex app-server account and rate-limit protocol](https://developers.openai.com/codex/app-server/) · [Codex authentication](https://developers.openai.com/codex/auth/)

## Refresh, privacy and alerts

The bridge shares successful readings for 60 seconds, and backs off after a failure. Upstream throttling produces a five-minute cooldown. Android caches the last successful response in DataStore, applies retry deadlines and exponential backoff, and stores the pairing token with an Android Keystore AES-GCM key. Backup and cleartext traffic are disabled. There are no analytics or ads.

WorkManager requests connected-network work every 15, 30 or 60 minutes; Android chooses actual execution times. Doze, force-stop, device shutdown, or an unreachable host can delay a reading. WorkManager persists periodic work across ordinary reboots. Dashboard and widget timestamps identify the observation time. Widgets calculate countdowns at render time, not every minute. Date displays use the phone's local time zone. After a predicted reset passes, a new upstream reading must confirm the quota.

Notifications are optional and off by default. Each observed quota window can announce below 25%, below 10%, or exhausted, once per severity. A later window boundary and decreased usage after the prior reset confirm a reset. Imported snapshots never generate alerts. If your account does not expose a particular window, the monitor cannot calculate it.

## Build and verify

Use JDK 17 and Android SDK Platform 35 with Build Tools 35.0.0.

```sh
python3 -m unittest discover -s bridge -v
./gradlew testDebugUnitTest assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

On a disposable API 35 emulator, run `bash scripts/device-check.sh` for the device suite. The script installs the app, grants widget binding to its test host, captures the actual dashboard and three Glance widget layouts, and smoke-checks launching after a reboot. Screenshots use the disconnected state to avoid publishing an account's data. The **android-device-evidence** CI artifact contains captured PNGs and test reports when the device job completes. Launcher grid sizing and background throttling also depend on your phone manufacturer.

## Architecture

The Compose dashboard and settings observe a ViewModel backed by DataStore. Manual and WorkManager refreshes share one serialized repository operation. This validates the HTTPS response, records success or a safe error message, evaluates notification thresholds, and requests an update for each installed widget. Three Glance providers select their layout by available space. The offline importer uses the same snapshot validation.

## Signed releases

Set the GitHub Actions secrets `SIGNING_KEYSTORE_B64` (base64-encoded keystore), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, and `SIGNING_KEY_PASSWORD`. Push a version tag such as `v0.1.0` to build and verify the signed APK. Download **codex-usage-release** from that run. Signing secrets are required for tagged release builds. Keep a private backup of your keystore.
