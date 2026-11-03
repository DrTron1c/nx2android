# Switch 2 games: one .nup container, or a dump folder (base/, updates/<version>/, dlc/), into the layout the port's later stages read: extract/v<version>/{ExeFS, RomFS.img} and
# aoc/<index>.img in the build folder (nxnative/scripts/pack_nup.py, pack_dump.py). These files are not encrypted, so such a port needs no prod.keys, and it works only for a game with
# a profile. Upstream builds them for the Mac; on Android every one of them is experimental until it has been played on a device.
import importlib.util
from dataclasses import dataclass, field
from pathlib import Path

from porter.paths import NXNATIVE
from porter.upstream import BuildError, say


def _load(name):
    spec = importlib.util.spec_from_file_location(f'porter_{name}', NXNATIVE / 'scripts' / f'{name}.py')
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m


@dataclass
class Source:
    kind: str                                   # 'nup' or 'dump'
    path: Path
    app: int                                    # the title ID
    versions: list = field(default_factory=list)   # what can be packed: 0 is the base game
    dlc: list = field(default_factory=list)     # DLC title IDs

    def name(self): return self.path.name


def is_switch2(app): return f'{app:016X}'.startswith('04')   # the Switch 2's title IDs


def find(folder):
    """The Switch 2 game in a folder, or None: a .nup file in it, or the folder itself in the dump layout"""
    folder = Path(folder)
    nups = sorted(p for p in folder.iterdir() if p.is_file() and p.suffix.lower() == '.nup') if folder.is_dir() else []
    if len(nups) > 1: raise BuildError(f'more than one .nup file in {folder}: keep one game per folder')
    if nups:
        pn = _load('pack_nup')
        try:
            with pn.Nup(nups[0]) as n:
                app, _, vers, dlc = pn.scan(n)
                return Source('nup', nups[0], app, sorted(vers), sorted(dlc))
        except (pn.Error, OSError) as e: raise BuildError(str(e)) from e
    pd = _load('pack_dump')
    if not pd.is_dump(folder): return None
    try: app, vers, dlc = pd.scan(folder)
    except pd.Error as e: raise BuildError(str(e)) from e
    return Source('dump', folder, app, sorted(vers), sorted(dlc))


def newest(src): return f'v{max(src.versions, default=0)}'


def has_version(src, ver): return int(ver.lstrip('v')) == 0 or int(ver.lstrip('v')) in src.versions


def extract(src, work, ver):
    """Version ver ('v655360') → (work/extract/<ver>, work/aoc or None). A repeat run keeps what is the same, as the packers do"""
    v = int(ver.lstrip('v')); out = work / 'extract' / ver; aoc = work / 'aoc'
    if v and v not in src.versions: raise BuildError(f'{src.name()} has no version {ver} (it holds {", ".join(f"v{x}" for x in src.versions) or "the base game only"})')
    if src.kind == 'nup':
        pn = _load('pack_nup')
        try:
            with pn.Nup(src.path) as n:
                app, base, vers, dlc = pn.scan(n)
                pn.pack_version(n, vers[v] if v else base, base, app, out, say)
                pn.pack_dlc(n, app, dlc, aoc, say)
        except (pn.Error, OSError) as e: raise BuildError(str(e)) from e
    else:
        pd = _load('pack_dump')
        try:
            app, vers, dlc = pd.scan(src.path)
            pd.pack_version(vers[v] if v else src.path / 'base', app, out, say)
            pd.pack_dlc(app, dlc, aoc, say)
        except pd.Error as e: raise BuildError(str(e)) from e
    return out, aoc if src.dlc else None
