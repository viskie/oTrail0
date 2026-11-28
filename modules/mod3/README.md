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
- The keypad rows are 4% taller than 2.1.1 for more reliable touch input while still leaving the reclaimed screen area to the terminal. Terminal output stays anchored while typing unless you scroll away from the bottom.
- The clipboard row collapses to the height of its text when shown, saving vertical space for the terminal.
- Tabs, PTYs, command history and scrollback survive rotation, returning to the map/extensions and normal backgrounding. Reopening the manager restores the same sessions. If Android kills the module process, the last tab snapshot is restored on next open and each tab starts a fresh shell.

Long-press a terminal line to select the word under your finger. Keep dragging to extend the selection across lines, then tap **COPY** in the selection bar (or **ALL** to select the whole transcript and **DONE** to clear). Tapping a normal key or switching tabs clears the selection. This replaces the previous behaviour where long-pressing a canvas-rendered terminal selected nothing.

The full manager bundles xterm.js 5.5.0 and FitAddon 0.10.0. Each tab has a terminal grid, a separate alternate screen and up to 3,000 lines of scrollback. Changing the viewport updates the Debian PTY dimensions. Swiping vertically in a full-screen TUI (Codex, Freebuff and similar) sends wheel events; TUIs without mouse reporting fall back to PageUp/PageDown.

Tap **Aa** to choose oTrail Dark, AMOLED or High contrast, switch between System Mono and JetBrains Mono, set the font size from 10 to 24, or collapse the clipboard and keyboard rows. The shell keypad uses 11 sp labels with rows 4% taller than 2.1.1; the bottom navigation row wraps to its text height.

## Input and clipboard

The built-in keypad sends shell escapes for arrows, Backspace and Delete, so cursor editing works as it does in a terminal. Hardware keyboards map printable keys, Backspace, Delete, arrows, Home/End, Tab, Escape and Enter to the same shell input.

Readline's erase replies are applied immediately to the terminal buffer. Backspace therefore removes the character on screen at once instead of waiting for Enter to redraw the prompt.

- `COPY` copies the terminal selection, or the full transcript when nothing is selected.
- `PASTE` injects clipboard text into the active shell.
- `SEL ALL` selects the terminal for a manual copy.
- Shortcuts: `Ctrl+Shift+C` copy, `Ctrl+Shift+V` paste, `Ctrl+Shift+A` select all.

## Compatibility

Debian Shell 2.1.3 requires host oTraiL 0.4.38 (61) or newer and GOD 4.2.0. It uses the host's packaged PRoot binaries. Shells share the app UID; the fake Debian root is not Android root.
