# SMS module

Car-style SMS inbox, conversation view and composer.

## Features

- **INBOX** — conversations grouped by address, newest first, with search across number,
  contact name and message text; unread threads are highlighted.
- **THREAD** — bubbles for the last 300 messages with a large inline reply bar; the list
  scrolls to the newest message.
- **NEW MESSAGE** — number + message composer with multipart sending via `SmsManager`.
- **Contact names** — resolved from `READ_CONTACTS` when granted; raw numbers otherwise.
- **Permissions** — the module requests `READ_SMS`, `SEND_SMS` and `READ_CONTACTS` itself and
  handles `permissionResult`. Pair a new message with a denied send permission and the module
  explains what to grant instead of failing silently.

New incoming messages are read from the system SMS provider; the module does not register a
broadcast receiver because module packages cannot add manifest receivers. Sends are handed to
the carrier, so delivery reports are not shown. Errors go to the **LOGGER** module.

## Requirements

Requires host oTraiL 0.4.40 (63) or newer, because the host manifest declares the SMS and
contacts permissions. Android 11+. The app does not need to be the default SMS app to read the
inbox or send, but this module cannot replace the system messaging app.
