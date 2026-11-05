# nxnative's own tools, used as libraries: the NSP script's steps (scripts/build_ipa_from_nsp.py: NSP scan, extraction, rewrite, shader split, stage reuse, progress), the game profiles
# (dm/games/game.py), the guesses from game files (dm/games/profile_guess.py), the control data reader (dm/tools/nacp.py) and the Android profile (dm/android/tools/android_profile.py).
# Importing them instead of copying keeps one implementation of each step for iOS and Android; what differs on Android (the rewrite target, the Vulkan shaders, the host, the APK)
# is in porter.pipeline.
import importlib.util, sys

from porter.paths import DM, NXNATIVE

for d in (DM / 'games', DM / 'tools', DM / 'android/tools'):
    if str(d) not in sys.path: sys.path.insert(0, str(d))

import android_profile   # noqa: E402
import game              # noqa: E402
import nacp              # noqa: E402
import profile_guess     # noqa: E402


def _load_nsp():
    spec = importlib.util.spec_from_file_location('build_ipa_from_nsp', NXNATIVE / 'scripts/build_ipa_from_nsp.py')
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    m.nacp, m.pg = nacp, profile_guess   # module globals its main() sets
    if sys.platform != 'darwin': m.can_clone = lambda d: False   # APFS clones (clonefile) exist only on a Mac
    files = m.profile_files   # the shader stages' reuse keys: android.tsv changes no shader (an edit to it re-split and re-translated Viractal's 25,464 shaders)
    m.profile_files = lambda g: [p for p in files(g) if p.name not in ('android.tsv', 'icon.png')]   # nor does the app's icon
    return m


nsp = _load_nsp()
BuildError, PROG, say = nsp.BuildError, nsp.PROG, nsp.say
# shader_vk.py's first version that made today's bundle: it stands for the file in the Vulkan shaders' reuse key, so changes in how the tool runs (progress, going on after a stop) keep an hour's
# work on a tablet. A change to what the bundle holds must give a new value here (any new text does: sha256 of it): else old bundles stay in use
nsp.PINNED['shader_vk.py'] = '061a67a41e51ba89914f6ec3307f2f2eb894f38f6e78bea13afc2afa1f3877b6'
# what the shaders were last made with: a game's note, and a split that reads the same shaders. A change to either would cost a tablet an hour of translating the same shaders again
nsp.PINNED['README.md'] = '48abe0a1c8b92f55ce6642125a6941c97edd52ecf0663f1152051aced7686c20'
nsp.PINNED['shader_split.py'] = '8826f2b0e270928ecc1a4f767749f83de15b6b72a005aa9ed089f17352a65b4e'
