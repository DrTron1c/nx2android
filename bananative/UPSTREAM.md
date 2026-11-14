# Banana Native in nx2android

This folder is Banana Native (moceure's Android port of seedds' nxnative), copied whole so that Switch 2 games run on its engine: its launcher app, its Vulkan
replay (`dm/gfx/vk_android`), its host, its shader translator and its on-device Prepare. Switch 1 games keep nx2android's own engine (`../nxnative`, `../porter`).

| | |
|---|---|
| Source | `moceure/BananaNative` on git.eden-emu.dev, through its GitHub mirror `hidayat077/BananaNative` (commits by moceure, checked against the Eden repository's history) |
| Version | v0.3.14 (`849d2fb`) and the issue digest commits after it, `0bac816` (2026-11-12) |
| Copied | 2026-11-13, unchanged (less `.github/` and `.forgejo/`, whose workflows run only at a repository's root) |

## What this fork changes

Each change is its own commit after the copy (`git log -- bananative`), so a newer Banana Native can be copied in the same way and the changes put back on top:

- `-PnxFork=true` (android/app/build.gradle): the fork's own app, `org.nx2a.launcher`, "nx2android engine", that installs beside Banana Native and its test app,
  with the play app's behaviour, signed with the fork's key (`.github/workflows/launcher.yml` builds it). It has no icon in the app list: nx2android's builder app
  (`../builder`, `Engine.java`) is the front. It installs and updates the engine, starts a game on it (`<package>.Launch`, android/FRONTENDS.md), pins its
  home-screen icon with the game's own picture, and opens the engine's pages for Prepare and the game settings.
- nx2android's look: its on-screen gamepad themes, the player's own designs (`../nxnative/dm/android/themes`; Pokopia's in its HUD style, with L3 and R3), packed by
  `nxThemeAssets` and drawn by `ThemePad.java` and `TouchControls.java`; the builder's palette for the in-game panel and the pages (`src/variant-nx2a/res`,
  `SidePanel.java`, BuildConfig.NX_FORK).
- Frame generation through a game's hitches (`dm/gfx/vk_android/lsfg/lsfg_pacer.c`).
- nx2android's touch rules for its themed gamepad (`dm/host/android/input_map.c` NXT_STYLE_NX2A, `touchControlsSetStyle`), as its own engine has them
  (`../nxnative/dm/android/front_android.c` touch_event): full tilt at a stick's ring with no dead zone, each control the rounded box it is drawn as with its reach,
  the spot between two face buttons pressing both, the D-pad's eight equal sectors, any number of fingers on a control, and floating sticks (the panel's controls rows).
- The save in step with the game's other versions: the fork's app sets `DM_SAVE_SYNC` to `<storage>/nx2android/save-sync` (a user line wins), as nx2android's
  per-game apps do, and sends the save when the app leaves the screen and before Stop ends it (`nxhost_save_flush`; `dm/host/save_sync.c` is Banana Native's).

## Licences

nxnative and Banana Native are MIT (`LICENSE`). The Lossless Scaling frame generation port (`dm/gfx/vk_android/lsfg`) and the translator built from Ryujinx's shader
code (`dm/third_party/ryujinx`) are GPL-3.0-or-later, which makes the app GPL-3.0-or-later as a whole (`android/NOTICE.md`).
