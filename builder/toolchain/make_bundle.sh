#!/data/data/com.termux/files/usr/bin/bash
# Runs inside Termux's arm64 image (.github/workflows/builder.yml). Installs what a port needs on a device, proves it can build a game's host there, and packs it as toolchain.tar.gz.
# The app unpacks this in its own storage and points LD_LIBRARY_PATH and PYTHONHOME at it, so these programs run from another folder than the one they were built for.
set -euxo pipefail
W=/workdir
export PREFIX=/data/data/com.termux/files/usr
yes | pkg update -y || true
pkg install -y python clang lld llvm shaderc android-tools dotnet-runtime-10.0 python-pillow python-pip libzstd openssl ca-certificates cmake make ninja
pip install --no-input lz4 capstone pyyaml fonttools pycryptodome backports.zstd
python -c 'import lz4.block, capstone, yaml, fontTools, Crypto, PIL; from backports import zstd; print("python modules fine")'
"$PREFIX/bin/clang" --version | head -1; dotnet --list-runtimes; adb version | head -1; glslc --version | head -1
# the NDK's sysroot and builtins beside the toolchain, wrapped to look like an NDK folder to build.py and porter
N=$W/bundle/ndk; H=$N/toolchains/llvm/prebuilt/linux-aarch64/bin; S=$N/shader-tools/linux-aarch64
mkdir -p "$H" "$S"
printf 'Pkg.Desc = nx2android toolchain bundle\nPkg.Revision = 27.2.12479018\n' > "$N/source.properties"
wrap() { printf '#!/system/bin/sh\nexec "$PREFIX/bin/%s" %s "$@"\n' "$2" "${3:-}" > "$1"; chmod 755 "$1"; }
wrap "$H/clang" clang '--sysroot="$ANDROID_NDK_HOME/sysroot"'
wrap "$H/clang++" clang++ '--sysroot="$ANDROID_NDK_HOME/sysroot"'
wrap "$H/llvm-strip" llvm-strip
wrap "$S/glslc" glslc
RES=$("$PREFIX/bin/clang" -print-resource-dir)
mkdir -p "$RES/lib/linux"; cp "$N"/rt/* "$RES/lib/linux/" || true
# proof: a whole game's host, built with this toolchain under bionic
export ANDROID_NDK_HOME=$N NX2A_NDK_HOST=linux-aarch64 NX2A_PREBUILT=$W/bundle/prebuilt NX2A_HOME=$W/home DOTNET_ROOT=$PREFIX/lib/dotnet DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 PYTHONPATH=$W
cd "$W"
python nxnative/dm/android/build.py --game 0400A3A01EE4C000 --ndk "$N" --out /tmp/host --release --lib --jobs "$(nproc)" 2>&1 | tail -30 | tee "$W/ci/ondevice-host.log"
ls -la /tmp/host/libdmhost.so | tee -a "$W/ci/ondevice-host.log"
python -m porter doctor 2>&1 | tee "$W/ci/ondevice-doctor.log" || true
python -m porter profiles --all 2>&1 | grep -E '^04' | tee -a "$W/ci/ondevice-doctor.log"
dotnet "$W/bundle/prebuilt/dmsh_xlate/dmsh_xlate.dll" --source-sha 2>&1 | tee -a "$W/ci/ondevice-doctor.log"
"$W/bundle/prebuilt/nx_extract/nx_extract" 2>&1 | head -2 | tee -a "$W/ci/ondevice-doctor.log" || true
# pack
pkg uninstall -y cmake ninja make || true
cd "$PREFIX/.."
tar --format=gnu -cf - --exclude='usr/share/doc' --exclude='usr/share/man' --exclude='usr/share/info' --exclude='usr/share/terminfo' --exclude='usr/include' --exclude='usr/var' --exclude='usr/tmp' --exclude='usr/lib/python3*/test' --exclude='usr/lib/python3*/__pycache__' --exclude='*.a' --exclude='usr/lib/libclang.so*' usr | gzip -6 > "$W/out/usr.tar.gz"
