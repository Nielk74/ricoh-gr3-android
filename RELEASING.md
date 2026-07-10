# Releasing

Releases are automated by [`.github/workflows/release.yml`](.github/workflows/release.yml).
Pushing a tag matching `v*` builds a signed APK + AAB and publishes them to a
GitHub Release for that tag.

## Required GitHub secrets

Configure these under **Settings → Secrets and variables → Actions**:

| Secret           | Description                                                        |
| ---------------- | ------------------------------------------------------------------ |
| `KEYSTORE_B64`   | Base64-encoded release keystore (`.jks`). See below.               |
| `STORE_PASSWORD` | Keystore (store) password.                                         |
| `KEY_ALIAS`      | Alias of the signing key inside the keystore.                      |
| `KEY_PASSWORD`   | Password for the signing key (often equal to `STORE_PASSWORD`).    |

If any of these are missing, the release build falls back to producing an
unsigned artifact rather than failing — but a published release should always
be signed, so set all four.

## Generating a keystore

Create a keystore once and keep it safe (losing it means you can no longer ship
updates that Android/Play accepts as the same app):

```bash
keytool -genkeypair -v \
  -keystore release-keystore.jks \
  -alias ricohgr3 \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storepass 'CHANGE_ME_STORE' \
  -keypass  'CHANGE_ME_KEY' \
  -dname "CN=Ricoh GR3 App, O=Ricoh GR3, C=US"
```

Then produce the base64 value for the `KEYSTORE_B64` secret (single line, no
wrapping):

```bash
base64 -w0 release-keystore.jks > keystore.b64
# On macOS (no -w flag): base64 -i release-keystore.jks | tr -d '\n' > keystore.b64
```

Paste the contents of `keystore.b64` into the `KEYSTORE_B64` secret, and set
`STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` to the values you used above.

## Cutting a release

The tag name (minus the leading `v`) becomes `versionName`; the workflow's run
number becomes `versionCode`.

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow then:

1. Computes `VERSION_NAME` (`1.0.0`) and `VERSION_CODE` (`github.run_number`).
2. Decodes `KEYSTORE_B64` to a file and exports the signing credentials.
3. Runs `./gradlew assembleRelease bundleRelease`.
4. Creates the GitHub Release and uploads the signed `.apk` and `.aab`.

## Local release builds

Contributors without the signing secrets can still build a release locally; the
artifact is simply left unsigned:

```bash
./gradlew assembleRelease
```

To sign locally, supply the same parameters as environment variables or Gradle
properties (`-P`):

```bash
KEYSTORE_FILE=/path/to/release-keystore.jks \
STORE_PASSWORD='...' KEY_ALIAS=ricohgr3 KEY_PASSWORD='...' \
VERSION_NAME=1.0.0 VERSION_CODE=1 \
./gradlew assembleRelease
```

`KEYSTORE_FILE` (a path) and `KEYSTORE_B64` (inline base64) are interchangeable;
if both are present, `KEYSTORE_FILE` wins.
