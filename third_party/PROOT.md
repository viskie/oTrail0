# PRoot native executables

The Debian Shell runs Debian userspace through PRoot. Four executable files are
shipped in `app/src/main/jniLibs/arm64-v8a/`. They are named as Android native
libraries so the APK installer extracts them into the app's executable
native-library directory; Termux itself is **not** required or used.

- Upstream: https://github.com/termux/proot (PRoot 5.1.107.102)
- Builds: Termux `proot`, `libtalloc` and `libandroid-shmem` 0.7 `aarch64` debs
  from https://packages.termux.dev/apt/termux-main/pool/main/
  (`proot_5.1.107.102_aarch64.deb`,
  `libtalloc_2.5.0_aarch64.deb`,
  `libandroid-shmem_0.7_aarch64.deb`)

| File | Contents | SHA-256 |
| --- | --- | --- |
| `libproot.so` | `bin/proot` (PIE, bionic) | `ecad8df0b00f1f48ef4050d31ae8cf7dcfbf5ac51242e66dabe9a7537176e428` |
| `libtalloc.so` | `libtalloc.so.2.5.0` | `d3a31956a1dc739ce28cff702103f5d043861cacdc5670fd91350957f269cf46` |
| `libandroid-shmem.so` | `libandroid-shmem.so` | `2ec1856bf6430dd5c65e5a6f274dcc1dc6338ab6539fce1154a02ba97c60d646` |
| `libprootloader.so` | `libexec/proot/loader` | `1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5` |

Why this build replaces the previously bundled statically linked glibc PRoot
5.5.0: Android's app seccomp filter traps syscalls a static glibc PRoot makes
(`rseq`), so the old binary was killed by `SIGSYS` and the shell exited with
159 (`128 + 31`) right after rootfs extraction. The Termux build is linked
against bionic, handles `SIGSYS`, and enables its seccomp-accelerated ptrace
mode on Android.

Local modifications to the downloaded artifacts (length-preserving, so the
binaries stay valid):

- `libtalloc.so` still declares `NEEDED libtalloc.so.2`; the file is renamed
  from `libtalloc.so.2` as required by Android's `jniLibs` naming.
- The Termux library search path `/data/data/com.termux/files/usr/lib` is
  rewritten to `$ORIGIN` in the `RUNPATH`/`DT_NEEDED`-adjacent string space of
  `libproot.so`, `libtalloc.so` and `libandroid-shmem.so`, so the loader finds
  its dependencies next to itself.

`DebianShell` launches PRoot with `PROOT_LOADER` pointing at
`libprootloader.so` in the same native-library directory. That loader is what
lets PRoot start the Debian `bash` ELF whose interpreter
(`/lib64/ld-linux-x86-64.so.2` or `/lib/ld-linux-aarch64.so.1`) does not exist
on the Android host; Termux's default loader path is not used.

Verified on an x86_64 API 34 emulator with the same 5.1.107.102 build: the old
static glibc PRoot exited 159 and the Termux PRoot exited 0 running
`echo PROOT_SHELL_OK` in a Debian bash.

Licenses: PRoot GPL-2.0 (`PROOT_COPYING`), talloc GPL-3.0 as packaged by Termux
(`TALLOC_COPYING`), libandroid-shmem BSD-3-Clause
(`LIBANDROID_SHMEM_LICENSE.txt`). The Debian module ships these three texts in
`LICENSES/`.
