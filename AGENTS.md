# Repository Guidelines

## Project Structure & Module Organization

This is an Android Gradle project named `legado`. The main application module is `app`, with source code under `app/src/main/java`, resources under `app/src/main/res`, assets under `app/src/main/assets`, Room schemas under `app/schemas`, unit tests under `app/src/test`, and instrumented tests under `app/src/androidTest`. Shared modules live in `modules/book` and `modules/rhino`. GitHub Actions workflows are in `.github/workflows`; project documentation is in `docs`.

## Build, Test, and Development Commands

- `.\gradlew.bat :app:assembleAppDebug` builds the debug APK.
- `.\gradlew.bat :app:assembleAppRelease` builds the release APK; signing requires release properties or CI secrets.
- `.\gradlew.bat :app:testAppDebugUnitTest` runs local JVM tests.
- `.\gradlew.bat :app:connectedAppDebugAndroidTest` runs Android instrumentation tests on a connected device or emulator.
- `.\gradlew.bat :app:lintAppDebug` runs Android lint for the debug variant.
- `.\gradlew.bat clean` removes Gradle build outputs.

Use `-Pabi=arm64-v8a` or `-Pabi=armeabi-v7a` when building ABI-specific APKs, matching the release workflows.

## Coding Style & Naming Conventions

Code is primarily Kotlin with Android XML resources. Use 4-space indentation, Android Studio defaults, and existing project patterns. Classes and objects use `PascalCase`; functions, properties, and resource names use `camelCase` or Android `snake_case` resource names. Keep UI strings in resource files, especially Chinese strings in `values-zh/strings.xml`. Do not commit local Maven mirror changes in `settings.gradle`.

## Testing Guidelines

Add local tests in `app/src/test/java` for pure Kotlin or rule-processing logic, and Android tests in `app/src/androidTest/java` for Room migrations, WebView behavior, and platform-dependent code. Name tests after the behavior being verified, for example `ParagraphRuleProcessorTest` or `MigrationTest`. Run the narrowest relevant Gradle test task before committing.

## Commit & Pull Request Guidelines

Recent history uses short imperative Chinese summaries, often prefixed by scope, for example `修复段落规则兼容和正文布局复用` or `fix huawei showbrowser rounded corners`. Keep commits focused and describe the user-visible behavior. Pull requests should include a concise summary, affected screens or modules, test results, and screenshots or recordings for UI changes. Mention migration, WebDAV, WebView, or backup risks explicitly when touched.

## Security & Configuration Tips

Do not commit signing keys, tokens, WebDAV credentials, or local Gradle mirror changes. Release signing values should come from CI secrets or local untracked properties. Treat WebView, JavaScript bridge, backup/restore, and remote import changes as high risk and keep their diffs small.
