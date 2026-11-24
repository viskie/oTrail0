# LOGGER — module events, errors and crash reports

Logger is a read-only diagnostics module for the oTraiL module host.

## What is captured

The host writes a rolling `files/module-logs/events.jsonl` log (2 MB current + 2 MB previous) for:

- module manager catalog refresh, download, verification, activation, deactivation, rollback and deletion;
- module package load and every module lifecycle call (`create`, `resume`, `pause`, `close`, `activityResult`, `permissionResult`);
- module task start, progress, completion, cancellation and failure;
- exceptions raised through the host and the app's uncaught-exception path;
- explicit `Host.log(event, detail)` calls from a module.

The logger does **not** capture passwords, keystrokes or arbitrary internal method calls. Downloaded modules must call `host.log(...)` for their own function-level events; the host records everything it observes at the module boundary.

## UI

`LOGGER` shows newest events first, with module/action/detail, a free-text filter, an errors-only toggle, refresh, clear and export. The crash/Android-exit report is appended below the events. `EXPORT` writes a `.txt` report through Android's document picker.

Requires host oTraiL 0.4.35 (58) or newer and Android 10+.
