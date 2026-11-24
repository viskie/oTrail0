# BT Keyboard — integrated Bluetooth HID remote

This module is now self-contained. It does **not** bundle or install a second APK and does not launch an external keyboard app. The former `com.example.btcombokeyboard` HID implementation was reduced to the parts this app needs and ported into the module:

- `BluetoothHidController` registers oTraiL as a Bluetooth HID device with Android's `BluetoothHidDevice` profile.
- `HidReportDescriptor` contains the keyboard (report 1), relative mouse/trackpad (report 2) and consumer/media (report 3) descriptors.
- `BtKeyboardModule` supplies the in-app device scan, pairing, connection and error UI, plus text input, modifier keys, navigation keys, trackpad and media buttons.

No separate APK asset is present. The module package contains only `classes.dex`, `module.json` and this README.

## Permissions

The base APK already declares the Bluetooth permissions in `app/src/main/AndroidManifest.xml`. The module requests the runtime permissions itself on Android 12+:

- `BLUETOOTH_CONNECT`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_ADVERTISE` (requested alongside the others)

On Android 11 and older the normal `BLUETOOTH` / `BLUETOOTH_ADMIN` declarations apply. No base APK update is needed for this module on host 0.4.34 or newer.

## Behaviour

1. Open the module and tap **GRANT ACCESS** if prompted.
2. Turn Bluetooth on if needed.
3. Tap **REGISTER HID**; the status line reports when Android accepts the HID app.
4. Make the host discoverable and tap **SCAN**. Paired and discovered devices appear in one list; tapping a new device begins pairing, tapping a paired device connects.
5. Errors from permission denial, Bluetooth being off, HID registration, discovery, pairing, connection and report writes are shown in the status area.

The phone is the HID peripheral. The other device is the host and must accept/confirm pairing and HID input.
