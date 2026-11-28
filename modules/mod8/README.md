# FILE MANAGER — Debian root filesystem and phone storage

Browse and edit the Debian image installed by the app alongside normal phone storage, without
PRoot or a second copy of the filesystem.

## Roots

- **DEBIAN** — `files/modules/data/mod1/debian/rootfs`, the verified Bookworm ARM64 image that
  GOD provisions. Requires GOD installed/activated; the module explains how to install it when
  the folder is missing. Read/write, including `/etc`, `/usr`, `/root` and `/home`.
- **PHONE** — shared storage (`/storage/emulated/0`), plus any SD card or system path through
  **GO TO…**. Android 11+ requires All files access; the module shows an **ALLOW ALL FILES**
  button that opens the system grant screen and reopens the folder on return.
- **APP DATA** — the app's private `files/` directory, so module data and GOD data are reachable.

## Basic operations

- Folder list with type, child count or file size, and modification time.
- Open folders, **↑ UP**, breadcrumb path bar, and **GO TO…** for arbitrary absolute paths.
- **NEW FOLDER**, rename, recursive delete with confirmation, and details (path, type, size,
  entries, modified time, read/write/execute).
- Copy or move any file or folder through a clipboard: choose **Copy to clipboard** or
  **Move to clipboard** from the row menu, navigate, then **PASTE**. Existing names get a
  `(1)`, `(2)`… suffix; a folder cannot be pasted into itself.
- Text files (source, config, logs, shell scripts and other UTF-8 text up to 512 KB) open in a
  selectable monospace reader; binary files show details instead.
- **PDF viewer:** `.pdf` files open in an offline page viewer built on Android's PdfRenderer,
  with previous/next page, page count and 50%–300% zoom that re-renders at screen resolution.
  Nothing is uploaded; rendering happens on the phone.

Requires host 0.4.30 (53) and Android 10+. No root is used: Debian is ordinary app-private
files, and phone storage access depends on the All files access grant.
