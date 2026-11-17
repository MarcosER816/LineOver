# LineOver

A personal, native Android image editor in the class of Photoshop + Lightroom:
non-destructive, GPU-accelerated, everything on the device. Built stage by stage.

- **Status:** Stage 0 (scaffolding) — see [docs/ROADMAP.md](docs/ROADMAP.md).
- **Requires:** Android 14+ (API 34), arm64, OpenGL ES 3.2.
- **Docs:** [AGENTS.md](AGENTS.md) · [Architecture](docs/ARCHITECTURE.md) ·
  [Design system](docs/DESIGN_SYSTEM.md) · [Tool catalog](docs/TOOL_CATALOG.md) ·
  [Changelog](CHANGELOG.md)

## Getting the APK

Every CI run on `main` (Actions → CI → latest run → Artifacts) has two downloads, each a
plain `.apk` (not a zip):

- **`lineover-<sha>.apk`**: the app. Release build, signed with your key. Install this one.
- `lineover-<sha>-debug.apk`: a test build with the debug menu (component gallery…),
  installed as a separate app (`dev.mark.lineover.debug`). Only needed when a stage's
  phone checklist says so.

Pushing a tag `v*` publishes a GitHub Release with the signed APK.

## Signing

The release APK is signed with your own key when these repository secrets exist
(Settings → Secrets and variables → Actions); otherwise it is signed with the debug key.

| Secret | Value |
| --- | --- |
| `KEYSTORE_B64` | The keystore file, base64-encoded on one line |
| `KEYSTORE_PASS` | Keystore password |
| `KEY_ALIAS` | Key alias (`lineover` below) |
| `KEY_PASS` | Key password (same as the keystore password for PKCS12) |

Create them once, for example in Termux on the phone:

```sh
pkg install openjdk-17
keytool -genkeypair -v -keystore lineover-release.jks -storetype PKCS12 \
  -alias lineover -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=LineOver"
base64 -w 0 lineover-release.jks > lineover-release.b64
termux-setup-storage && cp lineover-release.jks ~/storage/shared/   # your backup
```

Paste the content of `lineover-release.b64` into `KEYSTORE_B64`. Keep the `.jks` file and
its password somewhere safe: an app can only be updated by an APK signed with the same
key. Never commit the keystore.

## Building

There is no need to build locally; CI does it. The commands CI runs are listed in
[AGENTS.md](AGENTS.md#commands).
