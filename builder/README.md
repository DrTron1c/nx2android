# nx2android Builder

An Android app that ports a game on the device, with no PC. It runs the same `porter` as the desktop app.

- `app/`: the app (plain Java, no libraries). It targets SDK 28 on purpose: from 29 Android refuses to run programs from an app's own storage.
- `toolchain/make_bundle.sh`: builds the tools the app downloads, inside Termux's arm64 image, and proves they can build a game's host under bionic.
- `.github/workflows/builder.yml`: builds and publishes everything on the release `builder-tools`.

What differs from a port on a PC:

| PC | On the device |
|---|---|
| Google's NDK | Termux's clang with the NDK's sysroot, wrapped to look like an NDK folder (`NX2A_NDK_HOST=linux-aarch64`) |
| .NET SDK builds `nx_extract` and the shader translator | Both built once in CI, run with the .NET runtime (`NX2A_PREBUILT`) |
| Build folder beside the game | In the app's storage (`--work`): shared storage cannot run programs or hold hard links |
| Python's multiprocessing locks | Pipes (`toolchain/sitecustomize.py`): an Android app cannot create named semaphores |
| adb over USB | adb to this device over Wireless debugging: only the shell user can write to another app's `Android/data` folder |

Everything a run writes to the log is also in `nx2android/last-port.log` in shared storage.
