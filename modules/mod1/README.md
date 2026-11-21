# GOD — downloadable source studio (module ID `mod1`, version 3.2.0)

All screens and work execute inside oTraiL. No Termux, external application, build server or remote compiler is required. The editor, compiler and Debian shell controller are downloaded as GOD; the host APK contains only the signed module host and the ARM64 PRoot executable required by Android's native-execution policy.

## Debian shell

The Shell tab includes a checksum-pinned Debian Bookworm ARM64 root filesystem inside GOD and expands it under private `modules/data/mod1/debian/` without a second network download. oTraiL 0.4.22 raises the signed-module ceiling to 240 MB for this verified package. It launches `/usr/bin/bash --login` through PRoot and `script`, providing a PTY-backed session over GOD's terminal controls. Type `exit` or tap **Exit shell** to terminate only the Debian process and return to the GOD UI; oTraiL and its tracking service remain running.

The guest exposes these explicit bindings:

- `/project`: selected GOD source workspace, when one has been downloaded.
- `/god-data`: GOD's private data, tools, outputs and shell installation.
- `/app-data`, `/app-files`, `/app-cache`: oTraiL package-private directories under the same Android UID.
- `/external-app`: oTraiL's app-specific external-storage directory.
- `/shared-storage`: shared external storage, only after the user enables Android's special all-files access setting.
- `/dev`, `/proc`, `/sys`: Android views made available to PRoot; Android UID and SELinux restrictions still apply.

The shell's fake UID 0 permits Debian package management inside the rootfs; it is not Android root and cannot access another app's private data. Shell commands can modify or erase oTraiL data through `/app-data`, so launch requires an explicit warning confirmation. All-files access is a special Android permission and may make a general Play Store distribution ineligible; it is never silently granted. The current native executable supports `arm64-v8a` only.

## Use

1. On Android 15 or later, update oTraiL to 0.4.22 or later. Android still requires confirmation to install the main APK update.
2. Settings → Extension modules → Refresh versions → GOD → Download & install. Enter the installation password supplied by the owner, then Activate.
3. Tools → Refresh / choose toolchain. Review licenses and download the compatible toolchain. Downloads show sizes/progress and support Wi-Fi/cellular.
4. Project → Download source → Editor. The Sora/TextMate workspace shows its expandable project tree in the editor, opens files full-screen with a floating GOD return control, and provides file tabs, undo/redo, Java highlighting, find/replace, project-wide search, save/save-all and recoverable drafts.
5. Import the existing PKCS12 APK signing keystore in Tools. The password/alias and key remain private on this phone, outside source/snapshots/exports. A mismatched certificate is rejected; never uninstall the app to bypass it.
6. Save all & build APK. The dedicated in-app compiler process compiles Java with ECJ, builds DEX with D8, retains verified resources/native libraries, aligns native entries, then signs with apksig. APKs can be exported or installed after identity verification.
7. Generate GitHub SSH key → Download SSH public key. Add it to the GitHub account or repository deploy keys with write access. Configure the repository/branch and test the uploaded key.
8. APKs → Publish APK to GitHub → choose a unique release filename. The Java Git/SSH engine commits `releases/<name>.apk` and its SHA256 sidecar. It does not force-push or use GitHub Releases workflows. Initial Git synchronization can be large because old APKs are stored in the repository.

## First toolchain scope

The initial toolchain rebuilds the app's Java source roots. It preserves compiled resources, manifest, native routing/map libraries and dependency DEX from a verified template. Changes to protected resource/XML/build-system/BRouter source files fail explicitly; they are not silently ignored. New resource files are rejected. Tests and documentation are editable but are not part of the APK compile action. GOD's own source is visible for review; building a new module package is a separate publisher operation.

Toolchains are signed catalog entries with their own versions, download files, hashes, API/host compatibility and source baseline. The Tools selector permits updating a compiler without replacing the APK or editor, within API compatibility. Additional resource-capable engines can be published through that path.

## Storage and integrity

Workspaces, drafts, saved-file backups, five source snapshots, tools, keys and build outputs live under private `modules/data/mod1/`. They are separate from trail databases and map downloads. Installation uses the same package/signing identity; the recorder runs in its existing process. The compiler runs in `:compiler`, the editor in `:modules`. A process crash can interrupt a job, but never replaces the original app or publishes an unfinished APK. APK output becomes visible only after signing completes.

The SSH private key is generated in Android Keystore and is non-exportable. Only its public key can be downloaded. It survives in-place updates; uninstall removes it, so a new key would need registration. Export source ZIPs to retain work outside the app across uninstall. Private signing material is never included in source exports or GitHub commits.

Installation password is a salted PBKDF2 verifier in the signed catalog, not a plaintext credential in the APK. Executable module/tool integrity is authenticated by the publisher signature and checksums. Only trusted signed modules should be activated: they share the app UID and are not a security sandbox.

## Verification limits

The desktop validation runs the same Java compiler engine against real project sources and checks the resulting APK's signature, native-library alignment and retained resources. That does not prove ART/device compatibility. Phone-side module loading, compiler execution, Android Keystore SSH authentication and GitHub publication require device validation; do not treat this first release as device-validated while ADB is disconnected.
