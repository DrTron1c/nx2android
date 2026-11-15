"""Pokopia 2.0.1 camera-only opt-in, exact main NSO build.

The observation gates execute their original instructions and call back into
dm/host/android/pkp_aspect.c. Without DM_PKP_ASPECT, the callbacks make no changes.
UI layout/canvas are NOT part of this contribution.
"""
OBS = {
    '76a489f3bd3642fa5e18bd83d32c98ba': [
        (0x08cc128, 28, '398dc273', 'scene camera enters render view; x20 camera'),
        (0x1f12130, 29, 'aa0003f3', 'projection builder entry; x0 camera'),
        (0x1f12354, 30, '6d4123e9', 'perspective projection exit; x19 camera'),
        # nx2android (OBS_PKP_FRAME = 60, dm/host/android/pkp_sleep.c): the frame step's stp right before its first virtual call (blr x9 at +0xbfc55c), on the game thread once
        # a frame; the callback points x9 at the sleep mod's frame work, which then calls the getter x9 held
        (0x0bfc558, 60, 'a902a3e8', 'frame step, before its first virtual call; x9 its target (the sleep mod)'),
        # nx2android (OBS_PKP_GUIDE = 61): the key guide widget takes a set (Recompia's kFillSet: x0 the widget, w1 the set id); the title screen's sets are 152 and 183,
        # so a player's game reaching them is a Save and Quit (the sleep mod moves the clock on 6 hours for the next time in)
        (0x0ec5de0, 61, '72001c3f', 'key guide widget takes a set; w1 the set id (the sleep mod: the title screen)'),
    ],
}
BINDING = [
    {'what': 'Pokopia sleep mod frame gate in main v2.0.1 (nx2android)', 'now': 'build',
     'goal': 'game', 'purpose': 'runtime',
     'checked': 'main 76a489f3bd3642fa5e18bd83d32c98ba (disassembled 2026-11-15)',
     'why': 'the frame step and the save manager calls are at fixed places in this exact build',
     'blocked': 'future game builds require exact offset and original-word validation'},
    {'what': 'Pokopia camera aspect hooks in main v2.0.1', 'now': 'build',
     'goal': 'game', 'purpose': 'rendering',
     'checked': 'main 76a489f3bd3642fa5e18bd83d32c98ba (Fold 7 observation, 2026-11-11)',
     'why': 'camera layout/offsets are specific to this exact game build',
     'blocked': 'future game builds require exact offset and original-word validation'},
]