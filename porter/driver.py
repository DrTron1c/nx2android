# A custom Vulkan driver for a port: a .so, or a .zip as drivers for Android emulators come (meta.json names the library). It goes in the APK as libcustomdriver.so,
# because Android unpacks only lib*.so from an APK, and libadrenotools loads it from there (dm/android/gfx_vk_core.c, DM_VK_DRIVER).
#
# Another Vulkan driver for a port already on the device (python -m porter driver …), without the game's files: the installed APK is pulled, written again with the new
# driver library by nxnative/dm/android/apk/apkdriver.py and signed with the player's key (the same signer: it installs over the app, its saves stay), and env.txt's
# DM_VK_DRIVER row names the library (gfx_vk_core.c vk_init loads it through libadrenotools; no row: the device's driver). The game's data on the device is not touched.
# Only an APK ported with a driver carries libadrenotools: one ported without needs one port with --vulkan-driver first.
import json, re, shlex, subprocess, sys, tempfile, zipfile
from pathlib import Path

from porter import datapack, paths
from porter.adb import AdbError
from porter.paths import DM
from porter.upstream import BuildError, say

NAME = 'libcustomdriver.so'
EM_AARCH64 = 183


def check(blob, name):
    if blob[:4] != b'\x7fELF' or len(blob) < 20 or blob[4] != 2 or int.from_bytes(blob[18:20], 'little') != EM_AARCH64:
        raise BuildError(f'{name} is not a 64-bit Arm library, so it cannot be a driver for this device')


def from_zip(file):
    """The driver library's bytes and name"""
    try:
        with zipfile.ZipFile(file) as z:
            names = [n for n in z.namelist() if not n.endswith('/')]
            sos = [n for n in names if n.endswith('.so')]
            lib = None
            metas = [n for n in names if Path(n).name == 'meta.json']
            if metas:
                try: lib = json.loads(z.read(metas[0]).decode('utf-8', 'replace')).get('libraryName')
                except ValueError: lib = None
            pick = [n for n in sos if Path(n).name == lib] if lib else []
            if not pick and len(sos) == 1: pick = sos
            if not pick:
                vk = [n for n in sos if 'vulkan' in Path(n).name.lower()]
                if len(vk) == 1: pick = vk
            if not pick: raise BuildError(f'{Path(file).name}: no driver library found in it ({len(sos)} .so files, none named by meta.json)')
            others = [Path(n).name for n in sos if n != pick[0]]
            if others: say(f'[RED] the driver also has {", ".join(others)}, which are not packed: a driver that needs them will not load, and the game then uses the system driver')
            return z.read(pick[0]), Path(pick[0]).name
    except zipfile.BadZipFile as e: raise BuildError(f'{Path(file).name} is not a working zip: {e}') from e


def prepare(file, work):
    """file: the .so or .zip the player chose. Returns the library to pack, in work"""
    file = Path(file).expanduser().resolve()
    if not file.is_file(): raise BuildError(f'custom driver: no such file: {file}')
    if file.suffix.lower() == '.zip': blob, name = from_zip(file)
    elif file.suffix.lower() == '.so': blob, name = file.read_bytes(), file.name
    else: raise BuildError(f'custom driver: choose a .zip or a .so, not {file.name}')
    check(blob, name)
    dst = Path(work) / 'custom-driver' / NAME; dst.parent.mkdir(parents=True, exist_ok=True); dst.write_bytes(blob)
    say(f'custom GPU driver: {name} from {file.name}, {len(blob) / 2**20:.1f} MB')
    return dst


# ---- porter driver: another driver for a port on the device ----
ROW = 'DM_VK_DRIVER'


def env_with_driver(text, lib):
    """env.txt with its DM_VK_DRIVER row naming lib, in place of the old row (appended when there was none), or dropped when lib is '' (the device's driver)"""
    lines = text.splitlines(); out, done = [], False
    for ln in lines:
        if re.match(rf'\s*{ROW}\s*=', ln):
            if lib and not done: out.append(f'{ROW}={lib}'); done = True
            continue
        out.append(ln)
    if lib and not done: out.append(f'{ROW}={lib}')
    return '\n'.join(out) + '\n'


def installed_apk(adb, package):   # the installed APK's path on the device (ports are one APK, no splits)
    rows = [ln[len('package:'):].strip() for ln in adb.shell(f'pm path {shlex.quote(package)}', check=False).splitlines() if ln.startswith('package:')]
    if not rows: raise BuildError(f'{package} is not installed on the device')
    return rows[0]


def swap(adb, package, driver=None):
    """package's APK again with driver (a .so path), or with the device's own driver when None; env.txt names it. → {'package', 'driver', 'dropped'}"""
    if driver and not Path(driver).is_file(): raise BuildError(f'{driver}: no such driver library')
    with tempfile.TemporaryDirectory(prefix='nx2a_driver_') as t:
        src, out = Path(t) / 'installed.apk', Path(t) / 'driver.apk'
        say(f'{package}: pulling the installed APK')
        adb.run('pull', installed_apk(adb, package), src, timeout=600)
        r = subprocess.run([sys.executable, str(DM / 'android/apk/apkdriver.py'), str(src), str(out), str(paths.signing_key())]
                           + (['--driver', str(driver)] if driver else ['--system']), capture_output=True, text=True, errors='replace')
        if r.returncode: raise BuildError((r.stderr.strip() or r.stdout.strip()).splitlines()[-1])
        res = json.loads(r.stdout.strip().splitlines()[-1])
        adb.shell(f'am force-stop {shlex.quote(package)}', check=False)
        say(f'{package}: installing the APK with ' + (f'{res["driver"]}' if res['driver'] else 'no driver library (the device\'s driver)'))
        try: adb.install(out, package)
        except AdbError as e: raise BuildError(str(e)) from e
        env = f'{datapack.files_folder(package)}/env.txt'
        new = Path(t) / 'env.txt'; new.write_text(env_with_driver(adb.read_text(env), res['driver']), encoding='utf-8', newline='\n')
        adb.run('push', new, env)
    say(f'{package}: ' + (f'{ROW}={res["driver"]}' if res['driver'] else f'no {ROW} row: the device\'s driver') + ' (dropped: ' + (', '.join(res['dropped']) or 'none') + ')')
    return dict(res, package=package)
