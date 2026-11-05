# Installing a port on a device over adb: the APK, then the data pack into the app's files folder (porter/adb.py pushes it in resumable pieces).
# The player's own files there are kept: the pause menu's settings.txt, the logs, the Vulkan pipeline caches; saves are in the app's internal storage, which an update keeps.
import os, shlex, time
from pathlib import Path

from porter import datapack
from porter.adb import Adb, AdbError
from porter.upstream import BuildError, PROG, say


WAIT = 6 * 3600   # seconds an app on the device waits for Wireless debugging: everything before the install is done by then, and a stop here would only ask the player to tap again


def by_hand(adb, apk, package):
    """HyperOS can refuse an install over adb whatever its settings say. The APK goes to shared storage for the player to open, and the install goes on once Android has it"""
    stamp = lambda: adb.shell(f'dumpsys package {shlex.quote(package)} | grep -m1 lastUpdateTime; true', check=False).strip()
    was, dst = stamp(), f'/sdcard/nx2android/{apk.name}'
    adb.shell('mkdir -p /sdcard/nx2android'); adb.run('push', '-Z', apk, dst, timeout=300)
    say(f'[RED] This device refused the install. Open Files, go to nx2android, tap {apk.name} and choose Install (allow installs from Files if it asks). The port goes on by itself after that')
    PROG.set(step=f'waiting for you to install nx2android/{apk.name} from Files')
    for _ in range(WAIT // 5):
        time.sleep(5)
        if stamp() != was: say('installed by hand'); return
    raise AdbError(f'{apk.name} was not installed from Files')


def install(apk, data, adb_exe, serial=None, replace_signer=False, symbols=None):
    apk, data = Path(apk), Path(data)
    man = datapack.read_manifest(data)
    if not man: raise BuildError(f'not a data pack (no {datapack.MANIFEST}): {data}')
    if not apk.is_file(): raise BuildError(f'no APK: {apk}')
    if not adb_exe: raise BuildError('adb not found: install Android platform-tools, or set its path with `porter config adb <path>`')
    adb = Adb(adb_exe, serial, log=say)
    PROG.stage('install', step='finding the device')
    pushing = False
    try:
        dev = adb.pick(wait=WAIT if os.environ.get('NX2A_PREBUILT') else 0, waiting=lambda: PROG.set(step='waiting for Wireless debugging: turn it on in Developer options'))
        model = adb.prop('ro.product.model')
        say(f'device: {model or dev} ({dev}), GPU {adb.gpu() or "unknown"}')
        if 'bcn' in man.get('requires', []):   # the profile's requires: BC-compressed textures
            bc = adb.bc_textures()
            if bc is False: raise BuildError(f'{man["name"]} needs BC-compressed textures, which this device\'s GPU ({adb.gpu()}) cannot read')
            if bc is None: say(f'[RED] {man["name"]} needs BC-compressed textures; whether this GPU ({adb.gpu() or "unknown"}) reads them is not known')
        free = adb.free_bytes()
        files = datapack.files_folder(man['package'])
        have = adb.remote_sizes(files)
        need = man['bytes'] - sum(have.values())
        if free is not None and need > free: raise BuildError(f'the device has {free / 2**30:.1f} GB free; the game needs {need / 2**30:.1f} GB more')
        PROG.set(step=f'installing {apk.name}')
        try: adb.install(apk, man['package'], replace_signer=replace_signer)
        except AdbError as e:
            if 'INSTALL_FAILED_USER_RESTRICTED' not in str(e) or not os.environ.get('NX2A_PREBUILT'): raise
            by_hand(adb, apk, man['package'])
        PROG.set(step='letting the app create its files folder')
        if adb.prepare_app_files(man['package']): say('started the app once so Android made its files folder (it stops at once without its data)')
        PROG.set(step='pushing the game data', done=0, total=man['bytes'], unit='B'); pushing = True
        have = adb.remote_sizes(files)   # again: an install with --replace-signer uninstalls first, and Android deletes the files folder with the app
        adb.push_tree(data, files, keep=datapack.KEEP_ON_DEVICE, skip=(datapack.MANIFEST,), verify=lambda rel: rel.split('/', 1)[0] not in ('RomFS', 'RomFS.img', 'aoc'),   # the game's own files change only with their version
                      progress=lambda d, t, note: PROG.set(done=d, total=t, note=note), have=have)
        stale = [rel for rel in have if rel.split('/', 1)[0] in datapack.OWNED and not (data / rel).is_file()]   # the push only adds the pack's own files, which are never stale
        if stale:   # an older version's files, which the game would still find
            PROG.set(step=f'removing {len(stale):,} stale files')
            for i in range(0, len(stale), 64): adb.shell(f'cd {shlex.quote(files)} && rm -f ' + ' '.join(shlex.quote(r) for r in stale[i:i + 64]))
        adb.push_file(data / datapack.MANIFEST, f'{files}/{datapack.MANIFEST}')   # last: a pack on the device with its manifest is a complete one
    except AdbError as e:
        try: gone = pushing and adb.serial in [s for s, st, _ in adb.devices() if st == 'device'] and not adb.installed(man['package'])   # a device that went away is not an uninstall
        except AdbError: gone = False
        if gone:   # Android deletes an app's files folder with the app: a push into it then fails part-way (a size mismatch)
            raise BuildError(f'{man["package"]} was uninstalled on the device while its data was being pushed ({e})') from e
        raise BuildError(str(e)) from e
    # On the device nobody else can read a crash, and a crash before the app's log opens leaves that log as it was: the port itself tries the game and reports what Android saw
    if os.environ.get('NX2A_PREBUILT'):
        PROG.set(step='starting the game once to see that it opens')
        try: crash = adb.launch_report(man['package'], symbols)
        except AdbError as e: crash = None; say(f'could not try the game: {e}')
        if crash is None: say('the game started and was still running 12 seconds later')
        else:
            say('[RED] the game closed within 12 seconds of starting. What Android recorded:')
            for ln in crash: say(ln)
    PROG.end(f'{man["name"]} on {model or dev}')
    say(f'installed: open "{man["name"]}" on the device')
