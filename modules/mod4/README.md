# BT Keyboard — self-contained Bluetooth keyboard module

The module package contains the Bluetooth keyboard APK (`btkeyboard.apk`). It extracts the bundled APK to app-private storage and hands it to Android's package installer, then opens the installed keyboard. No GitHub download or external app source is used at runtime.

The bundled app is `com.example.btcombokeyboard` 1.1. Requires host 0.4.30 (53) and Android 10+. The host needs the install-unknown-apps permission enabled for oTraiL; the module prompts for it and continues after returning from Settings.
