
#!/usr/bin/env python3
import os
import sys
from pathlib import Path

EXCLUDE_DIRS = {
    ".git", ".gradle", ".idea", ".kotlin",
    ".cxx", ".externalNativeBuild",
    "build", "out", "target", "dist",
    "node_modules", "vendor", "deps",
    "venv", ".venv", "__pycache__",
    "generated", "intermediates", "outputs",
    "captures", "coverage", ".pytest_cache"
}

INCLUDE_EXT = {
    ".java", ".kt", ".kts",
    ".xml", ".gradle",
    ".properties", ".toml",
    ".c", ".cpp", ".cc", ".h", ".hpp",
    ".aidl", ".proto",
    ".json", ".yaml", ".yml",
    ".sh", ".py", ".md",
    ".pro"
}

INCLUDE_NAMES = {
    "gradlew", "settings.gradle",
    "build.gradle", "gradle.properties",
    "AndroidManifest.xml",
    "proguard-rules.pro",
    "CMakeLists.txt",
    "Makefile"
}

EXCLUDE_FILES = {
    "local.properties",
    "concat_android.py",
    "android-project-sources.txt",
    "google-services.json",
    "key.properties"
}

MAX_FILE_SIZE = 2 * 1024 * 1024

def concat_android(root):
    root = Path(root).resolve()
    output = root / "android-project-sources.txt"
    count = 0
    skipped = 0

    with output.open("w", encoding="utf-8") as out:
        out.write("ANDROID PROJECT SOURCE SNAPSHOT\n")
        out.write(f"Project: {root}\n")
        out.write("=" * 80 + "\n\n")

        for current, dirs, files in os.walk(root):
            dirs[:] = sorted(
                d for d in dirs
                if d not in EXCLUDE_DIRS
                and not d.endswith(".egg-info")
            )

            for name in sorted(files):
                path = Path(current) / name

                if name in EXCLUDE_FILES:
                    continue

                if (
                    path.suffix.lower() not in INCLUDE_EXT
                    and name not in INCLUDE_NAMES
                ):
                    continue

                try:
                    if path.stat().st_size > MAX_FILE_SIZE:
                        skipped += 1
                        continue

                    content = path.read_text(
                        encoding="utf-8",
                        errors="replace"
                    )

                    relative = path.relative_to(root)

                    out.write("\n" + "=" * 80 + "\n")
                    out.write(f"FILE: {relative}\n")
                    out.write("=" * 80 + "\n\n")
                    out.write(content)
                    out.write("\n")

                    count += 1

                except (OSError, UnicodeError) as e:
                    print(f"Skipped {path}: {e}")
                    skipped += 1

    print(f"Project: {root}")
    print(f"Sources combined: {count}")
    print(f"Skipped: {skipped}")
    print(f"Output: {output}")
    print(f"Size: {output.stat().st_size:,} bytes")

if __name__ == "__main__":
    concat_android(
        sys.argv[1] if len(sys.argv) > 1 else "."
    )
