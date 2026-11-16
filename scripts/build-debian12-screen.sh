#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=$(sed -n 's/^Version: //p' "$ROOT/packaging/debian12/control")
PACKAGE="$ROOT/build/debian"
STAGE="$PACKAGE/root"

rm -rf "$STAGE"
install -Dm755 "$ROOT/packaging/debian12/otrail_screen.py" "$STAGE/usr/bin/otrail-screen"
install -Dm644 "$ROOT/packaging/debian12/control" "$STAGE/DEBIAN/control"
install -Dm644 "$ROOT/packaging/debian12/README" "$STAGE/usr/share/doc/otrail-screen/README"
install -Dm644 "$ROOT/packaging/debian12/otrail-screen.desktop" \
  "$STAGE/usr/share/applications/otrail-screen.desktop"
install -Dm644 "$ROOT/packaging/debian12/otrail-screen.svg" \
  "$STAGE/usr/share/icons/hicolor/scalable/apps/otrail-screen.svg"
dpkg-deb --root-owner-group --build "$STAGE" "$PACKAGE/otrail-screen_${VERSION}_all.deb"
printf 'Built %s\n' "$PACKAGE/otrail-screen_${VERSION}_all.deb"
