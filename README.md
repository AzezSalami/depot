# Depot

A private app store for your own Android apps. It installs and updates apps on your
phone and your Wear OS watch straight from the **GitHub Releases of your private repositories**.

- `phone/` – the phone app (Android 8+)
- `wear/` – the watch app (Wear OS 3+)
- `core/` – shared logic: GitHub client, APK picker, installer, background update check

## Set up

1. **Install the phone app**: copy `dist/depot-phone.apk` to the phone and open it.
2. **Install the watch app** once over ADB (watch: Developer options → Wireless debugging):

   ```bash
   adb connect <watch-ip>:<port>
   ```

   ```bash
   adb -s <watch-ip>:<port> install dist/depot-wear.apk
   ```

3. **Create a GitHub token**: Settings → Developer settings → Fine-grained tokens. Select the
   repositories that hold your apps and grant one permission: **Contents: Read-only**.
4. In Depot on the phone: Settings → paste the token → Save. Then **Add repository**.
5. The token and repository list are sent to the watch automatically ("Share with watch").

## How a repository becomes an app

Depot looks at the newest published release of each repository and picks one `.apk` asset:

| "Install on" setting | Phone gets | Watch gets |
|---|---|---|
| Automatic (default) | APKs without `wear`/`watch` in the file name | APKs with `wear`/`watch` in the file name |
| Phone only | any APK | nothing |
| Watch only | nothing | any APK |

If a release has per-ABI APKs (`arm64-v8a`, …) the one matching the device is used.
`release` builds win over `debug`, and `unsigned` ones are a last resort.

A new release counts as an update. Three things your app builds must do:

- **Raise `versionCode`** on every release, or Android refuses the update.
- **Sign every release with the same key**, or Android refuses the update.
- **Attach the APK to the release**, for example with this workflow step:

  ```yaml
  - run: gh release create "v${{ github.run_number }}" app/build/outputs/apk/release/*.apk --generate-notes
    env:
      GH_TOKEN: ${{ github.token }}
  ```

## Updates

Depot checks about every six hours and notifies you. With "Install updates automatically" on,
Android 12+ applies updates silently for apps that Depot itself installed; the first install of
each app always asks you to confirm.

Depot can update itself: publish `depot-phone.apk` and `depot-wear.apk` as a release of the
repository you keep this code in, and add that repository in Depot.

## Build

```bash
./gradlew :phone:assembleRelease :wear:assembleRelease
```

```bash
./gradlew :core:testDebugUnitTest
```

Signing is read from `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) or from the `DEPOT_KEYSTORE_FILE`, `DEPOT_KEYSTORE_PASSWORD`, `DEPOT_KEY_ALIAS`
and `DEPOT_KEY_PASSWORD` environment variables. The keystore and `keystore.properties` are
git-ignored and must never be committed.

## Releases

Every push to `main` runs `.github/workflows/release.yml`, which tests, builds both signed APKs
and publishes release `v1.0.<run number>` with `depot-phone.apk` and `depot-wear.apk`.
`versionCode` is `100 + <run number>`, so each release installs over the previous one.
Change `depotVersionBase` in `build.gradle.kts` for a new major or minor version.

The workflow needs four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `DEPOT_KEYSTORE_BASE64` | the keystore file, base64-encoded |
| `DEPOT_KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `DEPOT_KEY_ALIAS` | `keyAlias` from `keystore.properties` |
| `DEPOT_KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

With the GitHub CLI, from the folder that holds the keystore:

```bash
base64 -w0 depot-release.jks | gh secret set DEPOT_KEYSTORE_BASE64
```

```bash
gh secret set DEPOT_KEYSTORE_PASSWORD
```

```bash
gh secret set DEPOT_KEY_ALIAS
```

```bash
gh secret set DEPOT_KEY_PASSWORD
```

**Keep the keystore safe.** The phone and watch apps must be signed with the same key to share
settings, and every future version of Depot must use it too.

## Dependency updates

Dependabot checks every day for newer versions of the Gradle dependencies and of the GitHub
Actions used here, and opens one pull request per kind. `.github/workflows/ci.yml` tests, lints
and builds it. Merging the pull request publishes a new release.

## Security notes

- The token is encrypted with an Android Keystore key and is only ever sent to `api.github.com`
  (downloads are redirected to GitHub's storage without it).
- "Share with watch" stores the token in the Wear OS data layer, which Google Play services
  syncs between the two devices. Turn it off to remove it from the watch.
- A read-only, repository-scoped token limits what a lost device can expose.
