# SVGA Load Test Implementation Plan

**Goal:** Add a persistent network SVGA configuration playground to the sample app.

**Architecture:** Keep form metadata, validation, preference storage and request snapshots in `SvgaLoadTestConfig.kt`. Build a native View form and a fixed preview in `SvgaLoadTestActivity.kt`, using the existing Coil 3 adapter. Each click validates an immutable snapshot and starts a new session; callbacks update only the current run.

**Tech Stack:** Kotlin, AppCompat, Android Views, SharedPreferences, Coil 3 SVGA loader, Android instrumentation, MockWebServer.

- [x] Add `app/src/androidTest/java/com/example/ponycui_home/svgaplayer/SvgaLoadTestActivityTest.kt`: exercise persistence, invalid settings, HTTP headers, non-default playback, repeated loading and network failure through real UI controls and local HTTP.
- [x] Run the test before implementation and confirm the missing activity fails.
- [x] Add `app/src/main/java/com/example/ponycui_home/svgaplayer/SvgaLoadTestConfig.kt`: enumerate supported fields/defaults, persist raw drafts, validate and convert to `SvgaRequest` and playback options. Reject contradictory cache flags, malformed headers and invalid numeric ranges.
- [x] Add `app/src/main/java/com/example/ponycui_home/svgaplayer/SvgaLoadTestActivity.kt`: upper scrolling form, load/reset actions, progress, current/previous results, lower preview and playback controls. Cancel replaced/destroyed sessions; retain all draft edits.
- [x] Register the activity in `app/src/main/AndroidManifest.xml` and add the entry to `MainActivity.kt`.
- [x] Run `rtk proxy ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.ponycui_home.svgaplayer.SvgaLoadTestActivityTest :app:assembleDebug --console=plain` with the installed Java 21 runtime. Expect all new tests and APK build to pass.
- [x] Inspect the page on a connected device, review the diff and run `rtk git diff --check`.

Review corrected the displayed decode dimensions (64 px buckets / CENTER source scale) and clarified shared-resource estimates. The dimension regression failed before the correction. All five instrumentation tests passed on CTR-AL20 / Android 12, including cache-only loading and static frame zero.
