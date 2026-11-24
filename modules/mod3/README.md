# Debian Shell manager (module ID `mod3`)

Debian Shell is the map launcher and shell-manager UI. It does **not** carry the Debian root filesystem any more.

## Shared image

The verified Debian Bookworm ARM64 root filesystem is packaged with **GOD 4.2.0 (`mod1`)**. Install and activate GOD first. Debian Shell starts GOD's `shellSetup` task once, and both GOD's simple Shell tab and this module launch the same private image at:

`files/modules/data/mod1/debian/rootfs`

If GOD is missing, Debian Shell explains what to install instead of downloading a second image.

## Shell manager

- The map icon appears only while this module is active.
- Long-press the map icon for the compact in-app shell; long-press again or press Back to release it.
- Open the module for the full manager.
- `+ TAB` starts another live PTY. Every tab keeps its own process, output, scroll position and command history.
- Tap a tab to switch. Long-press a tab to close it.
- `HIST` lists commands recorded in the current tab; selecting one sends it again.
- The keypad is compact by design, leaving the reclaimed height to the terminal. Terminal output stays anchored while typing unless you scroll away from the bottom.

## Input and clipboard

The built-in keypad sends shell escapes for arrows, Backspace and Delete, so cursor editing works as it does in a terminal. Hardware keyboards map printable keys, Backspace, Delete, arrows, Home/End, Tab, Escape and Enter to the same shell input.

- `COPY` copies the terminal selection, or the full transcript when nothing is selected.
- `PASTE` injects clipboard text into the active shell.
- `SEL ALL` selects the terminal for a manual copy.
- Shortcuts: `Ctrl+Shift+C` copy, `Ctrl+Shift+V` paste, `Ctrl+Shift+A` select all.

## Compatibility

Debian Shell 2.0.0 requires host oTraiL 0.4.34 (57) or newer and GOD 4.2.0. It uses the host's packaged PRoot binaries. Shells share the app UID; the fake Debian root is not Android root.
