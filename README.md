# CosFkAod

LSPosed module for ColorOS: use **全景沉浸 (panoramic AOD)** while a third-party theme is applied.

- `com.oplus.aod`: lifts the "third-party theme" check (`Y2.a.k`) that blocks panoramic, hides it in settings, and resets it to classic on start.
- Theme store (`com.heytap.themestore` 17.22.1 / `com.oplus.themestore` 17.11.18): if panoramic was selected and applying a theme resets AOD to classic, panoramic is restored.

Scope: 息屏 (com.oplus.aod) + 主题商店 + 主题基础服务, then reboot. Pick 全景沉浸 in AOD settings (not 灵感接续, which needs theme animation).

Names are obfuscated and build-specific (息屏 17.001.0021). Log tag: `CosFkAod`.

CI builds debug on every push; signed release when the `SIGNING_PASSWORD` secret is set. Publishing a GitHub Release (or running the `Release` workflow manually) builds and attaches the signed APK, and syncs metadata + release to `Xposed-Modules-Repo/io.github.sqmy_dor.panoramicaod` when `XPOSED_REPO_TOKEN` is configured.

## License

Apache License 2.0, see [LICENSE](LICENSE).
