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

## A long port

A port of a large game takes hours on a tablet, so the app assumes it will be interrupted.

- **Progress:** porter sends the list of steps first (`plan`), then its counts. `PortState.java` keeps the checklist, weighs the steps by how long each takes, and works out the time left. A step with no count of its own is measured by time against what it usually takes, and the device's own timings replace the typical ones after a run.
- **Pause:** `PortService` sends SIGSTOP to the port and everything it started, then SIGCONT to go on. It releases the wake lock and the Wi-Fi lock while paused.
- **Closing the app:** the port runs in a foreground service. If Android kills it, the service starts again and the port goes on, unless it was paused or cancelled. Opening the app after a force stop shows **Continue**.
- **Going on:** every step reuses what is finished. `shader_vk.py --resume` marks each finished batch and saves its place in the bundle, the host build keeps its object files, and the install keeps the pieces already pushed. The shader split starts again, and takes minutes.
- **Screen off:** a partial wake lock, a Wi-Fi lock so Wireless debugging survives, and Android's battery exemption. **Keep the screen on while porting** holds the screen on instead while the app is open.
- **Wireless debugging:** it gets a new port each time it is turned on. `Adb.attach` finds the new one over mDNS, and on a device the install waits up to 6 hours for it to come back instead of failing.

`shader_vk.py` is pinned in the Vulkan shaders' reuse key (`porter/upstream.py`), so a change to how it runs keeps earlier results. A change to what the bundle holds must change that value.
