# offreel

**Save Instagram reels to your phone, sort them into groups, and watch them later without the feed.**

You save a gym routine or a motivation reel on Instagram, and when you go back to watch it you end
up scrolling the feed instead. offreel fixes that: one tap while you're watching a reel saves the
video to your phone, into a group like *Gym* or *Motivation*. Later you open offreel and watch just
that group: full screen, swipe for the next one, no recommendations, no ads, works offline.

Android only. Not affiliated with Instagram or Meta.

## What it does

- **One-tap save from Instagram.** A small round button sits on the edge of the screen while
  Instagram is open (and only then). Tap it on a reel and it's saved to **Default**; tap a group in
  the sheet that pops up to put it there instead. Long-press the button to save straight into the
  group you used last.
- **Groups.** Make as many as you like. Open one to see its reels as a grid or a list; long-press to
  select several and move or delete them; every reel and group has a ⋮ menu.
- **Player.** Vertical swipe like Reels, tap to pause, **hold for 2× speed**, drag the progress bar
  to seek, tap the caption to read it all.
- **Feed.** Everything you saved, shuffled, or only the groups you pick.
- **Save to gallery.** Copy any reel (or all new ones, in Settings) to *Movies/offreel*.
- **Light and dark theme**, video quality (720p / 1080p / best), Wi-Fi-only downloads.

## Install

1. On your phone, open the **[latest release](../../releases/latest)** and download
   `offreel-<version>.apk`.
2. Open the file. Android asks you to allow installing apps from your browser the first time; allow it.
3. Open offreel and tap **Open settings** on the card at the top, then switch on
   **offreel save button** (Settings → Accessibility).
   If the switch is greyed out: *Settings → Apps → offreel → ⋮ → Allow restricted settings*,
   then try again. Android does this for every app installed from outside the Play Store.
4. Open Instagram. The round offreel button appears on the right edge. Drag it anywhere you like.

Needs Android 10 or newer (Android 11+ recommended) and an arm64 phone (practically all phones
from the last several years).

### Updating

Download and open the newer APK; it installs over the old one and keeps your saved reels.

## How to use it

1. In Instagram, open a reel (Reels tab, a post in your feed, or a reel someone sent you).
2. Tap the **offreel button**. A sheet slides up and the reel is saved to **Default** as soon as
   its link is ready (about a second).
3. Tap a group to move it there, or **New group** to make one. Or just close the sheet: it stays in
   Default.
4. Open offreel. **Home** shows your groups and anything still downloading. **Feed** plays
   everything shuffled. **Settings** has the theme, quality and gallery options.

Also works from Instagram's own **Share → offreel**, if you prefer that or the button ever stops working.

## How it works, and privacy

Instagram doesn't show a reel's link anywhere an app can read it, so when you tap the button,
offreel's accessibility service presses Instagram's own **Share → Copy link** for you (hidden
behind the save sheet) and reads the link from the clipboard. Android shows a small
"offreel pasted from your clipboard" notice when that happens; that's expected.

- The accessibility service only watches which app is on screen. It does nothing, and reads
  nothing, unless Instagram is open, and it only acts when you tap the button.
- No account, no login, no Instagram password. Only public reels can be saved.
- The video is downloaded with [yt-dlp](https://github.com/yt-dlp/yt-dlp), running on your phone.
  Nothing is sent to any server of ours; there isn't one.
- Saved videos live in offreel's private storage: other apps and your gallery can't see them, and
  uninstalling offreel deletes them. Use *Save to gallery* to keep copies.

### Limits

- **Photo posts** can't be saved yet (videos only). **Ads** can't be saved.
- Instagram occasionally refuses a download (about 1 in 8 reels in testing); those show
  *Couldn't save* with a retry button and an *Open in Instagram* link.
- When Instagram changes its app, the button may need an update. The share-menu way keeps working.

Please respect creators: offreel is for keeping reels for your own viewing, not for re-uploading
other people's work.

## Build it yourself

You need:

- **JDK 17 or newer.** The one bundled with Android Studio works (`/opt/android-studio/jbr` on Linux).
- **Android SDK** with platform **37** (Android Studio's SDK Manager, or `sdkmanager "platforms;android-37"`).
- An Android phone with **USB debugging** on, and `adb`.

```bash
git clone https://github.com/ABHILESH1412/offreel.git
cd offreel
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # where your Android SDK is
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as a separate app (`…offreel.dev`), so it can sit next to a released one.
The first build downloads Gradle and the libraries (a few GB); later builds are quick.

**Little RAM (8 GB or less)?** Close the browser, and if a build gets killed run it with one worker:

```bash
./gradlew --no-daemon -Dorg.gradle.workers.max=1 -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug
```

Or skip local builds entirely: fork the repo, and every push builds a debug APK on GitHub that you can
download from the run's *Artifacts*.

### Project layout

```
app/src/main/java/dev/offreel/
  capture/    accessibility service: the floating button, the save sheet over Instagram,
              and InstagramDriver (finds Share → Copy link on Instagram's screen)
  download/   yt-dlp downloads in the background (WorkManager)
  data/       Room database: groups, reels; settings; gallery export
  ui/         Home, group screen, player, Feed, Settings (Jetpack Compose)
scripts/      release.sh (publish a release), make-release-key.sh (one-time signing key)
docs/         releasing.md
spike/        the original test app used to prove the link-grabbing works
```

Kotlin, Jetpack Compose, Room, WorkManager, Media3 ExoPlayer, youtubedl-android (yt-dlp + ffmpeg).

## Releasing

Releases are built by GitHub Actions, not on your machine. Commit your work, then:

```bash
scripts/release.sh          # 0.1.0 -> 0.1.1
scripts/release.sh minor    # 0.1.1 -> 0.2.0
scripts/release.sh major    # 0.2.0 -> 1.0.0
```

It checks everything is committed and pushed, creates the GitHub release, waits for the build, and
prints the APK link. One-time setup (signing key, secrets) is in [docs/releasing.md](docs/releasing.md).
