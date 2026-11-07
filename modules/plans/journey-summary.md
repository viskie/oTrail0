# Journey summary

Status: planned; not implemented or enabled in Settings.
Stable ID: journey-summary

Optional daily distance, drive-duration and long-stop summaries. Reuse existing normalized fix and stop metrics, keeping estimated gaps distinguished from measured distance. The v1 snapshot is insufficient for daily grouping: extend a read-only host query API first, with work off the main thread and cancellation on shutdown. Validate old records, service restarts and no changes to raw journal rows.
