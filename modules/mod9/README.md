# PHONE module

Car-style phone front end with large touch targets: dial pad, contacts and call history.

## Features

- **DIAL** — 12-key pad with a large CALL button, backspace, and long numbers typed at 30sp.
  Uses `ACTION_CALL` when `CALL_PHONE` is granted, otherwise opens the system dialer
  (`ACTION_DIAL`) with the number prefilled, so the pad still works with no permission.
- **CONTACTS** — searchable list from `ContactsContract`; tapping a contact calls it.
- **RECENTS** — last 80 calls from the call log with IN / OUT / MISSED badges and relative time.
- **Permissions** — the module asks for `CALL_PHONE`, `READ_CONTACTS` and `READ_CALL_LOG`
  itself through the host activity and reacts to `permissionResult`. Missing permissions are
  shown as a banner and only disable the features that need them.

The module hands calls to the system telephony stack, which owns the in-call screen; it cannot
show or control an active call without being the default dialer. All errors (permission denial,
contacts/call-log read, call launch) go to the **LOGGER** module.

## Requirements

Requires host oTraiL 0.4.40 (63) or newer, because the host manifest declares the phone,
contacts and call-log permissions. Android 11+.
