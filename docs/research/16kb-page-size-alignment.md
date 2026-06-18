# Investigation: 16 KB page-size compatibility

Date: 2026-05-30
Status: Resolved (app verified 16 KB-compatible)

## Trigger

On a Xiaomi/HyperOS device, launching the **debug** build showed an "Android app
compatibility" dialog: *"This app isn't 16 KB-compatible. ELF alignment check failed"*,
listing native `.so` libraries as either **"LOAD segment not aligned"** or **"Unknown
error"**. Android 15+ ships devices with 16 KB memory pages, and Google Play requires
16 KB-compatibility for apps targeting SDK 35+.

## The two distinct problems

The two different messages turned out to be two different things:

1. **"LOAD segment not aligned"** — a real ELF problem: the library's `PT_LOAD` segments
   were aligned to 4 KB (`0x1000`) instead of 16 KB (`0x4000`).
2. **"Unknown error"** — *not* an ELF problem (those libraries were already 16 KB-aligned).
   A false positive from the HyperOS checker on a **debuggable** build.

## Empirical findings (readelf on the actual `.so`)

Inspected the ELF `LOAD` segment alignment of every flagged library at our pinned
dependency versions:

| Library | Source | Our version | ELF align | Action |
|---|---|---|---|---|
| `libwhisper.so`, `libggml-base.so`, `libwhisper_jni.so` | our CMake build | — | ❌ 4 KB | **fixed** (NDK flag) |
| `libsqlcipher.so` | `net.zetetic:sqlcipher-android` | 4.5.6 | ❌ 4 KB | **fixed** (→ 4.16.0) |
| `libLiteRt.so`, `liblitertlm_jni.so`, `libLiteRtClGlAccelerator.so` | `litertlm-android` | 0.11.0 | ✅ 16 KB | none (already OK) |
| `libdatastore_shared_counter.so` | `androidx.datastore` | 1.1.1 | ✅ 16 KB | none (already OK) |

Surprise: only **our whisper libs** and **SQLCipher 4.5.6** were actually misaligned.
LiteRT-LM 0.11.0 and DataStore 1.1.1 were already 16 KB-aligned. (Verified by downloading
the candidate AARs and running the NDK `llvm-readelf -l`.)

## Fixes applied

1. **Our native libs** — enabled 16 KB alignment for every CMake target (including the
   vendored whisper.cpp / ggml submodule targets) via
   `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` in `core/asr/build.gradle.kts`. This is the
   NDK r27 opt-in; r28 makes it the default. Verified `libwhisper.so` LOAD now `0x4000`.
   Commit `ecc6e80`.
2. **SQLCipher** — bumped `4.5.6 → 4.16.0`. net.zetetic ships 16 KB-aligned native libs
   since **4.6.1**. The API we use (`SupportOpenHelperFactory(byte[])`, `SQLiteDatabase`
   `openDatabase`/`openOrCreateDatabase` with `byte[]` keys, `rawQuery`/`rawExecSQL`) is
   unchanged, and SQLCipher keeps on-disk format compatibility across 4.x, so an existing
   encrypted DB opens unchanged (verified on-device). Commit `e4e2d1b`.

No change needed for LiteRT-LM or DataStore (already aligned) or for APK packaging.

## Packaging check (the "Unknown error")

The debug APK was already correct on the two packaging dimensions Play also requires:

- Native `.so` are **`Stored`** (uncompressed) — `extractNativeLibs` is unset.
- They are **16 KB zip-aligned** inside the APK — `zipalign -c -P 16 -v 4 app-debug.apk`
  reports every `lib/arm64-v8a/*.so` as `(OK)`.

So nothing to fix there. The "Unknown error" is the HyperOS checker failing to classify
the libraries on a debuggable build, not a real defect.

## Definitive verification

Built and installed a **release** APK (non-debuggable, R8/minify on). On the same device:

- **No "Android app compatibility" dialog at all** — neither the debuggable warning nor
  the 16 KB warning.
- App launches and runs (no R8 keep-rule regressions; the encrypted DB opens).

Conclusion: **the app is genuinely 16 KB-compatible.** The debug-build dialog is cosmetic
on HyperOS and disappears in release.

## Caveats / follow-ups

- The release APK was signed with the **debug key** (placeholder `signingConfig` in
  `app/build.gradle.kts`). A real signing keystore is needed before distributing a release
  artifact (e.g. on GitHub).
- On the debug build, the HyperOS dialog will keep appearing — expected, not a defect.

## References

- [Support 16 KB page sizes — Android Developers](https://developer.android.com/guide/practices/page-sizes)
- [SQLCipher for Android: 16 KB Page Size Support (since 4.6.1) — Zetetic](https://www.zetetic.net/blog/2025/06/26/sqlcipher-for-android-16kb-page-size-support/)
- NDK r27 `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` / `-Wl,-z,max-page-size=16384`.
