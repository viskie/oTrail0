# BROWSER module

Tab-free WebView browser with a developer panel across the top 40% of the module view.

## Features

- URL bar with smart input (adds `https://`, falls back to a Google search for bare words),
  back / forward / reload / home, and OPEN EXTERNAL.
- Developer panel occupies the top 40% (weight 4 of 10) and the page the remaining 60%.
  **DEV** hides or restores it.
  - **CONSOLE** — every page console message with level and source location, plus page-load
    errors and JavaScript evaluation echoes.
  - **NETWORK** — resource timings (`performance.getEntriesByType("resource")`) with status,
    duration, transfer size and initiator; refreshed after each load and polled while the
    tab is visible.
  - **JS** — evaluate an expression in the page and show the JSON or string result.
  - **INFO** — title, URL, viewport, DPR, cookie count, localStorage keys, and WebView agents.
- Remote debugging: `WebView.setWebContentsDebuggingEnabled(true)` enables `chrome://inspect`
  on a connected desktop browser.
- Cookies, DOM storage and third-party cookies are enabled; mixed content is allowed in
  compatibility mode so local dev servers work.

Errors (page load, scheme launch, external intent) go to the **LOGGER** module.

## Engine

The module uses the device's **Android System WebView** (Chromium). A downloaded DEX module
cannot ship native libraries or Android resources, so GeckoView/Chromium bundles are not an
option here. Update the system WebView app to update the engine.

Requires host 0.4.30 (53) or newer and Android 11+ with a WebView provider installed.
