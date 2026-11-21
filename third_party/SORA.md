# Sora Editor and TextMate assets

oTraiL 0.4.21 uses Sora Editor and its TextMate language module, version
0.24.7, from <https://github.com/Rosemoe/sora-editor>. Gradle resolves the
published Android libraries from Maven Central.

The Java grammar, Java language configuration and Darcula theme in
`app/src/main/assets/textmate/` are derived from the corresponding assets in
the Sora Editor 0.24.7 source distribution. The upstream LGPL-2.1-or-later
license is included at `app/src/main/assets/licenses/sora/LICENSE` and is also
shipped in the GOD module license directory.

Local modifications are limited to the reduced language registry and asset
layout used by GOD. The application source that initializes and uses these
libraries is available in this repository.
