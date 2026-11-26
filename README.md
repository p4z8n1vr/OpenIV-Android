# OpenIV Archive Editor for Android

An Android editor for local RPF7 archives, with English as the default interface language. Open an archive through Android’s file picker, browse directories and nested archives, search the current folder, and extract a file or folder to a location you choose. In the entry menu, you can edit UTF-8 text up to 1 MiB, replace files, rename entries, or delete entries. Touch and hold a folder to open its entry menu.

Use **Archive actions** to add a file, create a text entry, check archive information, or save an edited copy. To include a nested archive’s edits in its parent, choose **Apply to parent archive**, then save a copy of the parent. The app keeps working files in its private cache and uses Android’s Storage Access Framework for input and output.

The editor supports plain, OPEN, and AES RPF7 file tables. For AES, choose **Load AES key** on the start screen and select your own raw 32-byte key file. RSC7 resources are extracted as standalone resource files. NG-encrypted file tables and editing encrypted script resources are unsupported.

## Build

| Component | Version |
| --- | --- |
| Kotlin | 2.4.21 |
| JDK | 27.0.1.1 |
| Android Gradle Plugin | 9.5.1 |
| Gradle wrapper | 9.9.2 |
| Native language | C++23 |
| Compile and target SDK | 37 |
| Minimum SDK | 33 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |

Install JDK 27.0.1.1 and set `JAVA_HOME` to its directory. Set `ANDROID_HOME` to your Android SDK installation and install the SDK packages below.

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
./gradlew :app:assembleDebug :app:lintDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`. The archive reader streams DEFLATE data through C++23 and zlib, with a JVM fallback when the native library is unavailable.

The RPF7 format implementation was checked against the public CodeWalker format reference. [RPF7 format reference](https://github.com/dexyfex/CodeWalker/blob/master/CodeWalker.Core/GameFiles/RpfFile.cs)
