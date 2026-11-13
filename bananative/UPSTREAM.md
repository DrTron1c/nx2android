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

- `-PnxFork=true` (android/app/build.gradle): the fork's own app, `org.nx2a.launcher`, "nx2android", that installs beside Banana Native and its test app, with the play
  app's behaviour, signed with the fork's key (`.github/workflows/launcher.yml` builds it).
- Frame generation through a game's hitches (`dm/gfx/vk_android/lsfg`).
- A home-screen icon per game with a name and picture of your choice (`Shortcuts.java`).

## Licences

nxnative and Banana Native are MIT (`LICENSE`). The Lossless Scaling frame generation port (`dm/gfx/vk_android/lsfg`) and the translator built from Ryujinx's shader
code (`dm/third_party/ryujinx`) are GPL-3.0-or-later, which makes the app GPL-3.0-or-later as a whole (`android/NOTICE.md`).
