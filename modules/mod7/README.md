# POP3 — multi-mailbox client with an offline inbox

Configurable POP3 client that can hold several mailboxes at once. Each mailbox keeps its own
offline inbox on the phone, so previously downloaded mail opens instantly and without a network.
The module syncs every configured mailbox automatically when it is opened, and a SYNC button
refreshes on demand.

- **Mailboxes:** add, edit or remove any number of POP3 accounts (server, port, username,
  password, TLS on/off). Defaults to port 995 with TLS; plain POP3 is available for servers
  that need it.
- **Offline inbox:** message headers and text bodies are cached per account in the module's
  private storage and merged into an All view or filtered per mailbox. Unread messages are
  outlined; read state, subject, sender, date and body survive restarts and airplane mode.
- **Sync on open:** every open fetches only the messages missing from the cache (newest first,
  up to 40 per mailbox per sync, 400 kept per mailbox). UIDL is used when the server supports
  it, with a LIST-based fallback.
- **Reading:** tap a message for the full body (selectable text). "Delete from server" removes
  the offline copy at once and issues DELE on the next connection.
- **Theme:** black/cyan/purple console styling shared with the Bluetooth keyboard shell.

Requires host 0.4.30 (53) and Android 10+. Passwords are stored in the app's private
SharedPreferences and are only sent to the configured POP3 server over the chosen transport.

Sync, delete and UI failures are reported to the **LOGGER** module (`mod6`) through the host
event log with `sync-failed`, `delete-failed` or `action-failed` actions.
