# Ydroid

An Android video downloader powered by **yt-dlp**. Paste a link, pick quality, download to your phone. Supports 1000+ sites (YouTube, Instagram, TikTok, Twitter/X, Vimeo, …).

- Package: `org.opensourcecommunity.ydroid`
- Version: 1.1.0 (versionCode 2)
- Min SDK 34 (Android 14+) · Target/Compile SDK 37

## Screenshots

| Home | More options | Settings |
|---|---|---|
| ![Home](screenshots/home.png) | ![More options](screenshots/options.png) | ![Settings](screenshots/settings.png) |

## Features

- **Download** any yt-dlp-supported link with live progress
- **Quality**: Best / 4K / 1440p / 1080p / 720p / 480p / 360p / Audio-only
- **Formats**: MP4 / MKV / WebM video · MP3 / M4A / Opus audio
- **Subtitles**: off / embed English / English `.srt` / all languages
- **Playlists**: full or pick individual videos (flat-list picker, `--playlist-items`)
- **Auto `.m3u`**: playlist downloads write a `<title>.m3u` queue file; multi-select export in Files
- **Section cut**: download only `start–end` (`--download-sections`, ffmpeg-backed)
- **Queue**: jobs run one-by-one with Queued/Active/Done/Failed states, badge count, retry, pause/resume
- **Background downloads**: foreground service + notification, survives leaving the app
- **Resume**: `--continue` + retries pick up `.part` files after stop/kill; queue persists across restarts
- **YouTube login** (Settings → Account): in-app WebView sign-in saves `cookies.txt` to beat bot-checks and age gates
- **Update yt-dlp** in-app (Settings → Engine) when sites change extractors
- **Custom save**: subfolder under `Downloads/Ydroid` or SAF folder picker (copy-after-download)
- **Extras**: embed thumbnail/metadata, SponsorBlock removal, safe filenames, free-form yt-dlp flags
- **Files tab**: browse, select, delete (single + bulk), share, open, refresh
- **Appearance**: System / Light / Dark theme
- **Share-in**: send any link to Ydroid from other apps to auto-fill

## Install

Grab the APK from Releases (or `app/build/outputs/apk/release/`):

| Device | File |
|---|---|
| Most modern phones (arm64) | `app-arm64-v8a-release.apk` (~77 MB) |
| Older 32-bit phones | `app-armeabi-v7a-release.apk` |
| Emulator (x86_64) | `app-x86_64-release.apk` |
| Unsure | `app-universal-release.apk` (~215 MB) |

1. Uninstall any `com.example` build first (the app ID changed to `org.opensourcecommunity.ydroid`).
2. Install the APK (sideload — allow "install unknown apps" when asked).
3. Open once: yt-dlp + Python unpack on first run (~30 s), allow notifications when prompted.

Files land in **Downloads/Ydroid** (no storage permission needed on Android 14+ — scoped storage).

## Usage

1. **Home**: paste link → Paste (auto-fetches title) → Quality → Download.
2. **More options**: subtitles, playlist toggle + Pick videos, section cut, subfolder/SAF folder, extra flags.
3. **Queue** (play icon in the header): watch jobs progress, retry failures, clear finished.
4. **Downloads/Files**: open, share, delete, export `.m3u`.
5. **Settings**: YouTube login, yt-dlp update, theme, notifications.

## Permissions — why no storage prompt?

On Android 14+ there is no `WRITE_EXTERNAL_STORAGE` anymore. Writing to `Downloads/Ydroid` is whitelisted and listing your own files needs no permission, so the app never prompts for storage. `READ_MEDIA_VIDEO` is declared for reading shared media; `POST_NOTIFICATIONS` + foreground-service permissions power background download progress.

## Build from source

Requirements: JDK 17+, Android SDK with platform 37, ~8 GB free (native Python/ffmpeg libs are large).

```bash
./gradlew :app:assembleDebug      # debug APKs (fast)
./gradlew :app:assembleRelease    # release APKs (signed with debug key — replace signingConfig for public releases)
```

Per-ABI APKs are in `app/build/outputs/apk/<debug|release>/`.

## How it works

- [`youtubedl-android`](https://github.com/yausername/youtubedl-android) bundles Python + yt-dlp + ffmpeg (`--ffmpeg-location` is wired automatically for merges, MP3 extraction, section cuts, thumbnail/subtitle embeds).
- `YdlManager` builds the yt-dlp request (format selector caps like `bestvideo[height<=720]+bestaudio/…`, `--merge-output-format`, `--embed-thumbnail`, `--sponsorblock-remove`, …).
- `DownloadRepository` + `DownloadService` (foreground, `dataSync`) run jobs sequentially with a persisted queue; `--continue --retries 10` resumes `.part` files.
- Cookies from the login WebView are written as Netscape `cookies.txt` and passed via `--cookies`.

## Known limits

- Killing the app mid-download relies on service restart + `--continue`; reboot-persistence is not implemented.
- Some videos need login (age-gated) or fresh yt-dlp (extractor changes) — use Settings → Account / Update yt-dlp.
- Custom SAF folders are copy-after-download (yt-dlp writes to `Downloads/Ydroid` first).

## Disclaimer

Downloading videos may violate the source site's Terms of Service (e.g. YouTube). This project is for personal/educational use. Distribute and use responsibly — that's why releases are sideload APKs, not a Play Store listing.

## License

GPL-3.0 (inherited from the yt-dlp Android wrapper this app builds on). See `LICENSE`.
