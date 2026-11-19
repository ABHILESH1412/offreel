# Releasing offreel

Release APKs are built by GitHub Actions ([.github/workflows/android.yml](../.github/workflows/android.yml)),
so your own machine never has to run the memory-hungry release build.

- **Push to `main` / pull request:** GitHub builds a debug APK to catch mistakes. It's kept for 7 days
  under the run's *Artifacts* (`offreel-debug`).
- **Publish a release:** GitHub builds the signed, shrunk release APK (about 60 MB) and attaches it to
  that release as `offreel-<version>.apk`.

## One-time setup

1. **Create the GitHub repository** (e.g. `offreel`) and push this project to it.

2. **Create the release signing key** (once, ever):

   ```bash
   bash scripts/make-release-key.sh
   ```

   It asks for a password and writes `~/offreel-release.jks`. **Back the file and password up.**
   Every release must be signed with this same key; without it, phones refuse to install updates
   over the old version and people have to uninstall (losing their saved reels) first.

3. **Add two repository secrets** (*Settings → Secrets and variables → Actions → New repository secret*):

   | Name | Value |
   |---|---|
   | `OFFREEL_KEYSTORE_BASE64` | output of `base64 -w0 ~/offreel-release.jks` |
   | `OFFREEL_STORE_PASSWORD` | the password you typed in step 2 |

   Or with the GitHub CLI (replace `OWNER`):

   ```bash
   base64 -w0 ~/offreel-release.jks | gh secret set OFFREEL_KEYSTORE_BASE64 --repo OWNER/offreel
   ```

   ```bash
   gh secret set OFFREEL_STORE_PASSWORD --repo OWNER/offreel
   ```

## Making a release

Commit your changes, then run one of:

```bash
scripts/release.sh          # patch: 0.1.0 -> 0.1.1 (fixes)
scripts/release.sh minor    # minor: 0.1.1 -> 0.2.0 (new features)
scripts/release.sh major    # major: 0.2.0 -> 1.0.0 (big changes)
```

The script:

1. refuses if you have uncommitted changes, aren't on `main`, or `main` is behind GitHub;
2. checks the two signing secrets exist;
3. works out the next version from the latest `v*` tag, shows it, and asks you to confirm;
4. pushes any unpushed commits, creates the GitHub release (with notes generated from the commits);
5. waits for the *Android app* workflow and prints the APK's download link (Ctrl+C stops watching;
   the build carries on). Add `--no-wait` to skip waiting, `--yes` to skip the question.

By hand, the same is: push `main`, then `gh release create v0.2.0 --generate-notes`. The release's tag
must point at a commit that contains `.github/workflows/android.yml`, or nothing gets built.

The tag sets the version: `v0.2.0` → versionName `0.2.0`, versionCode `200`
(major × 10000 + minor × 100 + patch). Always release a higher version than the last one, or Android
won't install it as an update (the script enforces this).

## Installing on a phone

Download `offreel-<version>.apk` from the release on the phone and open it. Android asks to allow
installs from the browser the first time. Then turn on **Settings → Accessibility → offreel save button**;
if the switch is greyed out: *Settings → Apps → offreel → ⋮ → Allow restricted settings*.

The release app (`io.github.abhilesh1412.offreel`) installs next to development builds
(`…offreel.dev`, shown as "offreel" too). Each keeps its own saved reels.

## Building a release locally (if you ever need to)

It works on a low-RAM machine with these settings (one worker, smaller heap):

```bash
./gradlew --stop
```

```bash
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx2200m -XX:+UseSerialGC" -Dorg.gradle.workers.max=1 -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease -PversionName=v0.2.0
```

Without the signing environment variables (`OFFREEL_KEYSTORE`, `OFFREEL_STORE_PASSWORD`) it is signed
with the debug key: fine for trying on your own phone, not for publishing.
