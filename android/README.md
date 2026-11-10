# DOWR Android APK

This directory packages the existing offline HTML game into an Android WebView app.

- The exact repository-root `index.html` and `assets/` folder are copied to `android/app/src/main/assets/` by GitHub Actions before building.
- The app loads those assets securely from `https://appassets.androidplatform.net/assets/` via `WebViewAssetLoader`; no remote game server is required.
- Android minimum SDK: 24. Package: `ir.bashiramri.dowr`.
- GitHub Actions builds a **debug-signed APK intended for direct testing/sideloading**, not a production Play Store release.
- To get the APK, open **Actions > Build Android APK > latest successful run > Artifacts > DOWR-Android-debug-APK**.
- Local build (with Android SDK and Gradle 8.9): copy `index.html` into `android/app/src/main/assets/index.html`, copy `assets/` into `android/app/src/main/assets/assets/`, then run `gradle -p android assembleDebug`.
- App updates require downloading/installing a freshly built APK; a release-signing strategy is needed before distributing production builds. Existing website-localStorage data is separate from APK-localStorage data.
