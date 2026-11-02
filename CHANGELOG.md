# Changelog

## 0.2.0

Union is now **Tapll**.

- **New coordinates:** `com.tapll:tapll` (was `dev.useunion:union`), `com.tapll:tapll-compose` (was `dev.useunion:union-compose`). Gradle plugin `com.tapll` (was `dev.useunion.union`), extension `tapll { }` (was `union { }`).
- **New package:** `com.tapll` (was `dev.useunion`). Public entry point `Tapll` (was `Union`); `Union` stays as a deprecated typealias for one or two minor releases. Sample app id is `com.tapll.sample`. Consumer ProGuard rules updated.
- **Wire:** default endpoint `https://in.tapll.com` (was `https://in.useunion.dev`); headers `X-Tapll-Key` (was `X-Union-Key`) and, from the Gradle plugin, `x-tapll-key`, `x-tapll-build-id`, `x-tapll-app-version`, `x-tapll-app-build`; User-Agent `tapll-android/<version>`.
- **Gradle plugin:** build id asset is `assets/tapll-build-id.txt` (the SDK still reads `union-build-id.txt` from apps built with an older plugin); `TAPLL_SERVER_KEY` and `-Ptapll.enabled` replace `UNION_SERVER_KEY` and `-Punion.enabled`, the old names remain accepted as a fallback; tasks are `generateTapllBuildId<Variant>` and `uploadTapllMapping<Variant>`.
- **Migration:** on first launch the SDK moves `noBackupFilesDir/Union/<keyHash>/` (`identity.json` with the install id, `state.json`, `queue.ndjson`, `crashes/`) to `noBackupFilesDir/Tapll/<keyHash>/` and deletes the old tree, so the install id survives and an unsent queue or crash report is still delivered. Per file: only old exists, moved; both exist, the new one wins and the old is removed; neither, nothing happens.
- **Attributes:** the install id attribute for RevenueCat and Survicate is `tapll_install_id` (was `union_install_id`). The server accepts both.
- Thread names `tapll-pipeline`, `tapll-crash-writer`, `tapll-hang-watchdog`; log tag `Tapll`.

## 0.1.0

Initial release as `dev.useunion:union`: events, screens, features, identity, crash / ANR reporting, R8 mapping upload.
