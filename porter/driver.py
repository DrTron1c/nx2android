# A custom Vulkan driver for a port: a .so, or a .zip as drivers for Android emulators come (meta.json names the library). It goes in the APK as libcustomdriver.so,
# because Android unpacks only lib*.so from an APK, and libadrenotools loads it from there (dm/android/gfx_vk_core.c, DM_VK_DRIVER).
import json, zipfile
from pathlib import Path

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
