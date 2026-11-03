#!/data/data/com.termux/files/usr/bin/bash
# Runs the bundle the way the app does: unpacked in another folder, with Termux's own prefix gone, so anything still tied to Termux's path fails here and not on a tablet.
set -eux
W=/workdir; F=/data/data/com.termux/files/nx2a
mkdir -p "$F/nx2android/nxnative" "$F/home" "$F/tmp"
tar -xzf "$W/toolchain.tar.gz" -C "$F"
cp -r "$W/porter" "$F/nx2android/"; cp -r "$W/nxnative/dm" "$W/nxnative/scripts" "$F/nx2android/nxnative/"
cat > "$F/check.sh" <<C
export PREFIX=$F/usr HOME=$F/home TMPDIR=$F/tmp PATH=$F/usr/bin:/system/bin LD_LIBRARY_PATH=$F/usr/lib PYTHONHOME=$F/usr PYTHONPATH=$F/nx2android PYTHONDONTWRITEBYTECODE=1
export NX2A_HOME=$F/home/nx2android NX2A_PREBUILT=$F/prebuilt NX2A_NDK_HOST=linux-aarch64 ANDROID_NDK_HOME=$F/ndk DOTNET_ROOT=$F/usr/lib/dotnet
export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 DOTNET_EnableWriteXorExecute=0 SSL_CERT_FILE=$F/usr/etc/tls/cert.pem
cd $F/nx2android
echo '--- python'; $F/usr/bin/python3 -c 'import sys, lz4.block, capstone, PIL, ssl; print(sys.prefix, sys.platform)'
echo '--- doctor'; $F/usr/bin/python3 -m porter doctor
echo '--- host'; $F/usr/bin/python3 nxnative/dm/android/build.py --game 0400A3A01EE4C000 --ndk $F/ndk --out $F/host --release --lib --jobs 4 2>&1 | tail -6
ls -la $F/host/libdmhost.so
echo '--- translator'; $F/usr/bin/dotnet $F/prebuilt/dmsh_xlate/dmsh_xlate.dll --source-sha
echo '--- extractor'; $F/prebuilt/nx_extract/nx_extract 2>&1 | head -1
echo '--- adb'; $F/usr/bin/adb version | head -1
echo '--- apk'; $F/usr/bin/python3 -c 'print("python ok")'
echo ALL-RAN
C
# In this image /system/bin/sh is a link into Termux's prefix, which a real device's is not: only that one file stays where it was
O=/data/data/com.termux/files/usr; G=$O.gone
S=$(readlink -f /system/bin/sh); D=$(dirname "$S"); echo "sh is $S"
mv "$O" "$G"
export LD_LIBRARY_PATH=$G/lib
case "$S" in "$O"/*) "$G/bin/mkdir" -p "$D"; "$G/bin/cp" -L "$G${S#"$O"}" "$S";; esac
LD_LIBRARY_PATH=$F/usr/lib /system/bin/sh "$F/check.sh" > "$W/ci/relocated.log" 2>&1
