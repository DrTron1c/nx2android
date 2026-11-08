# Pokemon Pokopia (0400A3A01EE4C000): the rewriter's patches of this game's own builds. dm/tools/patch_nso.py loads every profile's patches.py and applies those of a module's NSO build ID;
# CUTS, OBS and FRAME_STEP as described there ("Registered patches"). Patches of Nintendo SDK module builds are in dm/tools/sdk_patches.py
obs_frame_mult, obs_fixed_step = 27, 28   # OBS_FRAME_MULT, OBS_FIXED_STEP in dm/host/dm.h
OBS = {
  # Pokemon Pokopia 2.0.1 (update v655360) main. The game speed fix (dm/android/speed_android.c). The main loop's frame step (main+0xbfc404) turns the measured frame time into a
  # frame multiplier in 60 Hz units that about 90 functions read (the float at main+0x34c03f0): 1.0 within 1.5 ms of 1/60 s, else frame time x 60 clamped to [1, 3]; at its join
  # point (main+0xbfc4fc) s0 holds the multiplier and s8 the frame time. Under 20 fps every frame lost what went past 50 ms: slow motion. In the Sync step's fixed-step mode
  # (main+0x89def8; byte +0xb4 of the main-loop object) the frame time is 1 / 60 s whatever the real time; before that division (main+0x89e398) s0 is 60.0, x28 this frame's tick,
  # x25 the previous frame's, d8 the tick frequency. Addresses and registers from pp-native's notes of the same build (github.com/mshivam019/pp-native, runtime/games/pkp/pkp_play.cpp,
  # main build 76a489f3bd3642fa5e18bd83d32c98bacc5f7d0e); the original words were not read here (no game files), so they are None: the rewriter reads them and leaves a gate out
  # if its instruction cannot move, and the host checks the registers over the first frames before it changes anything
  '76a489f3bd3642fa5e18bd83d32c98ba': [
    (0xbfc4fc, obs_frame_mult | (0 | 8 << 5 | 3 << 10) << 8, None, 'frame multiplier join point: s0 the multiplier, s8 the frame time, the game\'s limit 3'),
    (0x89e398, obs_fixed_step | (0 | 28 << 5 | 25 << 10 | 8 << 15) << 8, None, 'fixed-step frame rate before the division: s0 60.0, x28 now, x25 the previous frame\'s tick, d8 the tick frequency'),
  ],
}

# How general each patch is and could be (dm/tools/docs_patches.py "Binding", listed in docs/agents/game-patches.md): what, its binding now, the loosest it could have (goal),
# purpose, why; blocked or kept: why the gap stays open (cannot close yet, or left open on purpose); checked: what was checked on game files (else a judgement)
BINDING = [
    {'what': 'game speed fix: frame multiplier and fixed-step rate gates', 'now': 'build', 'goal': 'game', 'purpose': 'frame rate',
     'why': "the game's own frame step; a function fingerprint would carry it to other updates",
     'blocked': 'written without the game files: no fingerprint of the frame step yet'},
]
