# Road ahead

Status: planned; not implemented or enabled in Settings.
Stable ID: road-ahead

Extend existing cached road/landmark information with an optional upcoming-places panel. Prefer cached data; online refresh only under existing connectivity choices. No new recorder or GPS stream. Define distance limits, deduplication, background task ownership and cancellation before implementation. Reuse the core cache through a future read-only host API; do not open a competing trail database writer.
