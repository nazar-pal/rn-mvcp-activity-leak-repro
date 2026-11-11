# Android: ScrollView with `maintainVisibleContentPosition` that is never attached leaks Activities

On Android, a `ScrollView` that gets `maintainVisibleContentPosition` but is never attached to a
window is never unregistered from `FabricUIManager`.

- After each Activity recreation (here: a display density change), the destroyed Activity stays
  in memory. 10 recreations leave 11 Activities, and they are still there after 60 s and a
  `RUNNING_CRITICAL` trim. Without the prop, the count goes back to 1.
- Without any recreation, each deleted ScrollView of this kind leaves its listener behind:
  10 remounts leave 11 `MaintainVisibleScrollPositionHelper` instances registered.

A ScrollView that was attached and later detached is not affected: `onDetachedFromWindow` stops
the helper.

The app uses React Native core only. It was made from
[react-native-community/reproducer-react-native](https://github.com/react-native-community/reproducer-react-native)
(commit `fed06d9ba`), with the template's unused `react-native-safe-area-context` and
`@react-native/new-app-screen` removed, and the template's GitHub workflows removed.

All commands below run from the repository root.

## Versions

- react-native 0.88.1 (npm `latest`), react 19.3.0, New Architecture, Hermes. Template defaults
  otherwise. Same result on 0.89.0-rc.1 (npm `next`).
- Measured on two emulators: Android 17 (API 37, Google Play image, user build, arm64) and
  Android 10 (API 29, Google APIs image, userdebug build, arm64). No real device was tested.

<details>
<summary><code>npx @react-native-community/cli info</code> (home-directory paths and free memory removed)</summary>

```text
System:
  OS: macOS 27.2
  CPU: (15) arm64 Apple M5 Pro
  Shell:
    version: "5.9"
    path: /bin/zsh
Binaries:
  Node:
    version: 22.23.2
  Yarn: Not Found
  npm:
    version: 10.9.8
  Watchman:
    version: 2026.06.22.00
    path: /opt/homebrew/bin/watchman
Managers:
  CocoaPods:
    version: 1.16.2
    path: /opt/homebrew/bin/pod
SDKs:
  iOS SDK:
    Platforms:
      - DriverKit 27.2
      - iOS 27.2
      - macOS 27.2
      - tvOS 27.2
      - visionOS 27.2
      - watchOS 27.2
  Android SDK: Not Found
IDEs:
  Android Studio: 2026.2 AI-262.10968.63.2622.16730706
  Xcode:
    version: 27.2/27B52
    path: /usr/bin/xcodebuild
Languages:
  Java:
    version: 17.0.19
    path: /usr/bin/javac
  Ruby:
    version: 2.6.10
    path: /usr/bin/ruby
npmPackages:
  "@react-native-community/cli":
    installed: 20.2.0
    wanted: 20.2.0
  react:
    installed: 19.3.0
    wanted: 19.3.0
  react-native:
    installed: 0.88.1
    wanted: 0.88.1
  react-native-macos: Not Found
npmGlobalPackages:
  "*react-native*": Not Found
Android:
  hermesEnabled: true
  newArchEnabled: true
iOS:
  hermesEnabled: Not found
  newArchEnabled: false
```

Notes: Yarn 1.22.22 ran through Corepack, so the CLI does not see it. The Android SDK is
installed (platform `android-37.0`, build-tools 37.0.0, NDK 27.1.12297006, emulator 37.2.13) but
the CLI reports it as not found. iOS was not set up (no `pod install`), which is why the iOS
lines read "Not found" and `false`.

</details>

## What the app does

[`ReproducerApp/App.tsx`](ReproducerApp/App.tsx) renders a `ScrollView` inside a 40 dp tall
`View` with `removeClippedSubviews`. The ScrollView is laid out below its parent's bounds, so
`ReactViewGroup` keeps it out of the view hierarchy: it is created and gets its props, but it is
never attached to a window.

- **Switch to control (no prop)** renders the same ScrollView without
  `maintainVisibleContentPosition`. The choice survives an Activity recreation.
- **Remount hidden ScrollView** replaces the ScrollView with a new one (new `key`).

Android shows the button titles in capitals.

## Build and install

Release build, so no Metro is needed. The app uses Yarn 1 (the template's `yarn.lock`); if `yarn`
is not on your PATH, `corepack yarn install` works too.

```sh
(cd ReproducerApp && yarn install)
(cd ReproducerApp/android && ./gradlew :app:assembleRelease)   # add -PreactNativeArchitectures=arm64-v8a to build faster
adb install -r ReproducerApp/android/app/build/outputs/apk/release/app-release.apk
adb shell am start -W -S -n com.reproducerapp/.MainActivity
```

## Result 1: Activities pile up on recreation

The template's `MainActivity` does not declare `density` in `android:configChanges`, so a display
density change recreates it.

```sh
ReproducerApp/scripts/recreate.sh 10
```

[`recreate.sh`](ReproducerApp/scripts/recreate.sh) waits until the app's buttons are on screen,
then alternates the density between the current value and one 15% higher, 10 times. It prints
`Views` and `Activities` from `dumpsys meminfo` after each recreation, after 60 s idle, and after
`am send-trim-memory com.reproducerapp RUNNING_CRITICAL`, and then restores the density. It
stops with an error if a reading comes back empty. Set `ANDROID_SERIAL` if more than one device is
connected.

By hand, the same thing is:

```sh
adb shell wm density                 # note the current density
adb shell wm density 552             # any other value; this recreates MainActivity
adb shell wm density reset           # recreates it again
adb shell dumpsys meminfo com.reproducerapp | grep -E "Views:|Activities:"
```

For the control, restart the app (`adb shell am start -W -S -n com.reproducerapp/.MainActivity`), tap **Switch to control (no prop)**, then run the same commands. Without the restart, the Activities kept by the run with the prop are still in memory.

### Measured

`Activities` from `dumpsys meminfo`, official Release build of this app, cold start, then 10
recreations with a reading 10 s after each, then 60 s idle, then the trim:

| Device and build | cold | after recreations 1 to 10 | 60 s idle | after trim |
|---|---|---|---|---|
| API 37 emulator, 0.88.1 Release, with the prop | 1 | 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 | **11** | **11** |
| API 29 emulator, 0.88.1 Release, with the prop | 1 | 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 | **11** | **11** |
| API 37 emulator, 0.89.0-rc.1 Release, with the prop | 1 | 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 | **11** | **11** |
| API 37 emulator, 0.88.1 Release, control (no prop) | 1 | 2 each time | 1 | 1 |
| API 29 emulator, 0.88.1 Release, control (no prop) | 1 | 2 each time | 1 | 1 |

- `Views` grows by one whole view tree per recreation with the prop: 19 → 209 on API 37 (19 per
  tree) and 20 → 220 on API 29 (20 per tree). For the control it goes to 38 (API 37) or 40
  (API 29) and back to 19 or 20 after idle.
- The control's `2` is the previous Activity before the next GC.
- The first row was measured again with `recreate.sh 10` from a clean copy of this repository,
  following the commands above: the same numbers.
- `dumpsys meminfo` does not force a GC, so its count alone shows "not yet collected". The proof
  of retention is the heap dump below, and the fact that the prop runs stay at 11 after idle and
  trim while the control drops to 1.
- PSS is small and noisy in this tiny app, so it is not used as evidence. On API 37 it went from
  50.6 to 55.7 MB with the prop and from 51.1 to 53.5 MB for the control over the same 10
  recreations.

## Result 2: helpers pile up on remount (heap dump)

Each tap on **Remount hidden ScrollView** deletes a never-attached ScrollView and creates a new
one. The deleted view's helper is never stopped.

`am dumpheap` needs a debuggable app on a user (non-root) emulator image, so build the Release
with [`scripts/debuggable-release.gradle`](ReproducerApp/scripts/debuggable-release.gradle). It
only sets `debuggable = true` on the release build type; the JS bundle stays embedded.

```sh
(cd ReproducerApp/android && ./gradlew -I ../scripts/debuggable-release.gradle :app:assembleRelease)
adb install -r ReproducerApp/android/app/build/outputs/apk/release/app-release.apk
adb shell am start -W -S -n com.reproducerapp/.MainActivity
# Wait until the buttons are on screen (a debuggable build takes a few seconds),
# then tap "Remount hidden ScrollView" 10 times, or run: ReproducerApp/scripts/recreate.sh 4
adb shell am dumpheap -g com.reproducerapp /data/local/tmp/app.hprof    # -g: GC first
sleep 5
adb pull /data/local/tmp/app.hprof
hprof-conv app.hprof app-converted.hprof                                # from Android SDK platform-tools
ReproducerApp/android/gradlew -p tools/heap-check run --args="$PWD/app-converted.hprof"
```

[`tools/heap-check`](tools/heap-check) is a small Gradle program (Shark 2.14, the heap library
behind LeakCanary, from Maven Central; it runs with the app's Gradle 9.4.1 wrapper and JDK 17).
It prints how many `MainActivity` instances are in the dump and how many are destroyed, how many
`MaintainVisibleScrollPositionHelper` instances there are and how many are in
`FabricUIManager.mListeners`, and, for destroyed Activities, the shortest path from a GC root and
whether any is still reachable with `FabricUIManager.mListeners` ignored.

### Measured

`am dumpheap -g`, then `tools/heap-check`:

| Case | Device and build | Destroyed `MainActivity` | Helpers in `FabricUIManager.mListeners` |
|---|---|---|---|
| Cold start, no taps | API 37 emulator, debuggable 0.88.1 Release | 0 | 1 (the live hidden ScrollView) |
| 10 remounts, no recreation | API 37 emulator, debuggable 0.88.1 Release | 0 | **11** (10 deleted ScrollViews, 1 live) |
| 4 recreations | API 37 emulator, debuggable 0.88.1 Release | **4** | 5 (4 from destroyed surfaces, 1 live) |
| 4 recreations | API 29 emulator, official 0.88.1 Release | **4** | 5 |
| Control, 4 recreations and 10 remounts | API 37 emulator, debuggable 0.88.1 Release | 0 | 0 |

The 10-remount and the API 37 4-recreation rows were measured again from a clean copy of this
repository, following the commands above (4 recreations through `recreate.sh 4`): the same
numbers.

In every case with the prop, none of the registered helpers' ScrollViews is attached to a window
or has a parent. The deleted ones are no longer in the surface's view registry.

Output for the 4-recreation dump on API 37:

```text
MainActivity instances: 5 (destroyed: 4)
MaintainVisibleScrollPositionHelper instances: 5 (in FabricUIManager.mListeners: 5; their scroll views attached to a window: 0, with a parent: 0)

Shortest path from a GC root, the same for 4 destroyed MainActivity (array entries [0] [1] [2] [3]):
  GC root: JNI_GLOBAL
  com.facebook.react.fabric.FabricUIManager.mListeners
  java.util.concurrent.CopyOnWriteArrayList.array
  java.lang.Object[] [i]
  com.facebook.react.views.scroll.MaintainVisibleScrollPositionHelper.scrollView
  com.facebook.react.views.scroll.ReactScrollView.mContext
  com.facebook.react.uimanager.ThemedReactContext.mBase
  com.reproducerapp.MainActivity (mDestroyed = true)

Destroyed MainActivity reachable: 4
Destroyed MainActivity reachable with FabricUIManager.mListeners ignored: 0
```

Each destroyed Activity sits behind its own entry of the listener array, one per leaked
ScrollView. On API 29, `am dumpheap` also works with the official, non-debuggable Release
(userdebug image).

Not measured: the CPU cost of the leftover helpers. `FabricUIManager` calls every listener on
each mount (`didScheduleMountItems`, `willMountItems`, `didMountItems`, `didDispatchMountItems`).
For this helper, `willMountItems` runs `computeTargetView()` and `didMountItems` runs
`updateScrollPositionInternal()`; both return early when the ScrollView has no content view. The
other two callbacks are no-ops.

## Cause

In `ReactScrollView.setMaintainVisibleContentPosition` (the horizontal and nested scroll views
are the same), the helper is created and started when the prop is set:

```kotlin
maintainVisibleContentPositionHelper =
    MaintainVisibleScrollPositionHelper(this, false).also { it.start() }
```

`start()` adds the helper to `FabricUIManager.mListeners`. Only two places call `stop()`:
`onDetachedFromWindow`, and the prop being set back to null. A view that is never attached is
never detached, and neither view deletion nor surface stop calls `stop()`. When the Activity is
recreated, the helper keeps the old view, its `ThemedReactContext`, and through it the old
Activity.

## Possible fixes

Two changes were tested. Both diffs are against `main` at `b16032065`.

- **1. Stop the helper when the view is dropped** ([`fix-stop-on-drop.diff`](fix-stop-on-drop.diff)):
  keep the prop setter as it is, and stop the helper in `onDropViewInstance` of the three scroll
  view managers. `SurfaceMountingManager` calls `onDropViewInstance` from `deleteView` and from
  `stopSurface`. This keeps today's behaviour.
- **2. Start the helper only when attached** ([`fix-attach-check.diff`](fix-attach-check.diff)):
  in the prop setter of the three scroll views, call `start()` only if the view is attached.
  `onAttachedToWindow` already starts it otherwise. The diff also updates the KDoc of `start()`.

How they were tested: the changed classes were recompiled against the official 0.88.1
`react-android` release AAR and swapped into a copy of it, and this app was built against that
AAR. That covers the Kotlin change only, not a full React Native source build.

Measured on the API 37 emulator, Release build with the prop (debuggable Release for the heap
dumps):

| Build | `Activities` after 10 recreations, after 60 s idle, after trim | Helpers in `mListeners` after 10 remounts | Position check, left and right list |
|---|---|---|---|
| Official 0.88.1 | 11, 11, 11 | 11 | 0 px, 0 px |
| 1. Stop on drop | 2, 1, 1 | 1 (the live hidden ScrollView) | 0 px, 0 px |
| 2. Attach check | 2, 1, 1 | 0 | 0 px, 0 px |
| 1 and 2 together | 2, 1, 1 | 0 | 0 px, 0 px |
| Official 0.88.1, lists without the prop (negative control) | | | 488 px, 487 px |

After 4 recreations, the heap dump held 0 destroyed `MainActivity` with each of the three fixed
builds. The position check ran twice per build and gave the same result each time.

The position check uses [`tools/mvcp-behaviour-App.tsx`](tools/mvcp-behaviour-App.tsx) in place
of `ReproducerApp/App.tsx`. It has a list that is attached at mount (left) and a list inside a
clipping parent that is attached later, when **Reveal** moves it into view (right). Both lists are
scrolled by about two rows, then **Prepend 5** adds five rows at the top, and the row's on-screen
position was read before and after from `uiautomator dump` bounds (the driver script is not
included). 0 px means the position was kept. The negative control is the same app with
`maintainVisibleContentPosition` removed from both lists.

Behaviour difference:

- With 1, a ScrollView that is mounted but never attached stays registered, and its helper keeps
  running on every mount, until the view is dropped. That is today's behaviour.
- With 2, such a ScrollView does not listen, so it does not adjust its scroll position while it is
  detached. A ScrollView that was attached and then detached already behaves this way today.

`initView()`, which view recycling calls, sets the helper to null without calling `stop()`
(`ReactScrollView.kt` line 259, `ReactHorizontalScrollView.kt` line 292, `ReactNestedScrollView.kt`
line 267). That only matters for a helper that is still registered when its view is recycled, and
recycling (`enableViewRecyclingForScrollView`) is off by default. Fix 1 stops the helper
before `super.onDropViewInstance()` reaches the recycling code; that was not tested.

Not tested: recycling, horizontal and nested scroll views at runtime (only compiled), and real
devices.
