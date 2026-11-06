# Journal locking fix

TrailDb handles now share one reference-counted SQLiteOpenHelper/pool per absolute database path. Previously the GPS writer, UI, geocoder and backup could open independent pools against trails.db; Android did not serialize their competing writes inside one pool. Closing a temporary UI handle no longer closes the active tracker's pool. The last handle closes the pool normally, preserving fixture isolation and resource cleanup.

On Android 15+, normalized backup exports use a read-only transaction so they retain a consistent snapshot without reserving the writer. Earlier Android versions retain the existing nonexclusive transaction fallback. Local backup candidate output is buffered. Journal schema, source coordinates, stop timing and restore merge rules are unchanged.

Validation: clean APK build and lint pass; 22 isolated phone instrumentation checks pass, including concurrent writes/reads, handle ownership, exact GPS row counts, integrity_check and a GPS write completing while a backup snapshot remains open. In-place POCO install confirmed. Post-install integrity_check is ok; no pre-update fixes or stop IDs are missing. The current custom palette still has a duplicate color, so the unrelated 31-unique-colors JVM assertion remains a separate configuration issue.
