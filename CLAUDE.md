# Depot

A private Android app store. It installs and updates the owner's own apps on a phone and a
Wear OS watch from the GitHub Releases of private repositories. See `README.md` for the
user-facing setup.

## Layout

- `core/` – Android library with all logic and no UI. Package `app.depot.core`.
  - `Model.kt` – `RepoEntry`, `Release`, `Asset`, `AppState`, `Status`, `Busy`, `Target`, `Variant`.
  - `GitHub.kt` – REST client on `HttpURLConnection` + `org.json`. No third-party HTTP library.
  - `AssetPicker.kt` – decides which APK of a release belongs on this device.
  - `Settings.kt` – SharedPreferences storage; the token is encrypted with an Android Keystore key.
  - `Installer.kt` – `PackageInstaller` sessions and `InstallReceiver` for their results.
  - `Depot.kt` – the singleton that ties it together and exposes `StateFlow`s to the UIs.
  - `WatchLink.kt` – data layer paths, the capability name, and the pure Kotlin encoding of what the
    phone and watch send each other (`WatchState`, `WatchCommand`).
  - `UpdateWorker.kt` – WorkManager job, every 6 hours. `Notifier.kt` – notifications.
- `phone/` – Compose Material 3 app. `AppsScreen.kt`, `SettingsScreen.kt`, `WatchSync.kt` (sends
  settings), `WatchRemote.kt` (reads the watch's state, sends it commands).
- `wear/` – Wear Compose app. `MainActivity.kt` (whole UI), `ConfigService.kt` (receives settings
  and commands), `StateReporter.kt` (writes the watch's state), `DepotWearApp.kt` (runs commands).
- `app-common.gradle.kts` – reads signing settings for both apps.

## Commands

```bash
./gradlew :core:testDebugUnitTest
```

```bash
./gradlew :phone:lintDebug :wear:lintDebug
```

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease
```

Run all three before finishing a change. There are no instrumented tests and CI has no
emulator, so UI and install-flow changes need a real phone or watch to verify; say so when
a change could not be checked on a device.

## Rules that are easy to break

- **Phone and watch share `applicationId` (`app.depot.store`) and the signing key.** The Wear OS
  data layer only delivers between apps where both match. Never give them different ids.
- **The token goes to the API host only.** `GitHub.download` follows the asset redirect by hand
  so the `Authorization` header is not sent to the storage host. `GitHubTest` covers this; keep
  it passing and do not switch to automatic redirects.
- **No secrets in the repository.** `*.jks` and `keystore.properties` are git-ignored. CI gets
  the key from the `DEPOT_KEYSTORE_*` secrets.
- **`Depot.init` must run in `Application.onCreate`** of each app; receivers and the worker call
  `Depot.get`.
- **`Target` clashes with `kotlin.annotation.Target`.** Outside `app.depot.core`, import
  `app.depot.core.Target` explicitly; a star import is not enough.
- Logic that can be pure Kotlin stays out of Android classes so it can be unit-tested on the
  JVM (`CoreTest`, `GitHubTest` with MockWebServer).
- Wrap `Tasks.await` calls (data layer) in try/catch and keep them off the main thread; phones
  without Play services must still work.

## Phone and watch

Everything between the two apps goes over the Wear OS data layer. Names and encoding live in
`core/.../WatchLink.kt`; the manifest filters in `wear/` and `wear.xml` repeat them as text.

| What | Kind | Written by | Content |
|---|---|---|---|
| `/depot/config` | data item | phone (`WatchSync`) | token, repository list, auto-install |
| `/depot/watch-state` | data item | watch (`StateReporter`) | `WatchState` as JSON in `state`, plus `stamp` |
| `/depot/command` | message | phone (`WatchRemote`) | `WatchCommand` as JSON: `install` (repo key) or `check` |
| `depot_watch` | capability | watch (`res/values/wear.xml`) | marks nodes that take commands |

- The watch downloads and installs with its own token. Never send an APK or the token in a
  message; the token only travels in `/depot/config`.
- A command starts the watch process if needed (`ConfigService.onMessageReceived`) and is run by
  `DepotWearApp.handle` on the application scope, never on an activity or composition scope.
- The watch repeats a command's `id` as `ack` in its next state. The phone shows "Sending" until
  then, and treats 45 seconds without it as "the watch did not answer".
- `StateReporter` writes on every change of `Depot.apps`, but a change that is only download
  progress waits until a second has passed since the last write.
- Android decides whether an install needs a tap. `InstallReceiver` sets `AppState.needsUser` when
  it does, and the phone shows "Confirm on your watch". That is a state, not an error.
- Phone and watch can be on different versions. Only add fields to the JSON; decoding must keep
  ignoring unknown fields and commands and filling in missing ones (`WatchLinkTest`).
- `wear.xml` needs `tools:keep`, or resource shrinking removes the capability from release builds.

## How updates are detected

A repository's newest non-draft release is fetched and one APK asset is picked. The app is
"up to date" when the asset id recorded at the last install equals the picked asset id. The
package name is only known after the first download, when the APK is parsed. If the installed
`versionCode` already equals the APK's, the asset is recorded without reinstalling.

## Versions and releases

- `depotVersionBase` in `build.gradle.kts` is the major.minor part, changed by hand.
- The patch number and `versionCode` (100 + n) come from `DEPOT_BUILD_NUMBER`, which
  `.github/workflows/release.yml` sets to its run number. Local builds are build 0.
- Every push to `main` (except Markdown-only changes) publishes release `v<base>.<run>` with
  `depot-phone.apk` and `depot-wear.apk`. Keep those two file names: Depot uses "wear" in the
  name to send the right APK to the watch when it updates itself.
- `.github/workflows/ci.yml` tests, lints and builds every pull request.
- `.github/dependabot.yml` opens grouped dependency pull requests daily. Kotlin and the Compose
  compiler plugin share one version and must move together. A newer Android Gradle Plugin can
  need a newer Gradle wrapper (`./gradlew wrapper --gradle-version <x>`).

## Style

- Kotlin official style, 120 columns. Comments explain why, not what.
- UI text is plain sentence case and tells the user what to do next when something fails.
- Keep dependencies few; prefer the platform API when it is a few lines.
