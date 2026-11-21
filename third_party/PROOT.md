# PRoot native executable

`app/src/main/jniLibs/arm64-v8a/libproot.so` is the statically linked AArch64
PRoot 5.5.0 executable. It is deliberately named as an Android native library
so Android extracts it into the APK's executable native-library directory.

- Upstream: https://github.com/proot-me/proot
- AArch64 build: https://github.com/upleung/proot-releases/releases/tag/v5.5.0
- Original asset: `proot-v5.5.0-aarch64-static`
- SHA-256: `5792fc6b39f05ea99708f3acfc0d5f92e9433fc2b2923db95d58ba0b7049a829`
- License: GPL-2.0; see `PROOT_COPYING`

The build is a static ELF executable and does not need a separate runtime
`libtalloc.so`. GOD checks for the `arm64-v8a` ABI before exposing the shell.
