# Third-party notices

## Orbit

The Blockbench animation conversion rules in `tools/ax_anim.py` were adapted from
Orbit's `AnimationBuilder.processChannel` and its keyframe handlers by 17Artist.

- Source: https://github.com/17Artist/Orbit
- Reference revision: `3e415a286c8964b11a3290e262094a3344dcd66d`
- License: Apache License 2.0, included in `licenses/Apache-2.0.txt`.
- Changes: Python implementation, constant numeric channels only, no Bézier resampling,
  output to Nightstar's mesh/rig manifest instead of Bedrock animation JSON.

These portions retain their Apache 2.0 permissions; the first-party non-sale terms
do not override them. No Orbit binary is bundled.

Minecraft, Fabric, ArcartX and their respective names belong to their owners.
They are separately installed dependencies or optional integrations, not bundled products.
Catmull–Rom interpolation uses the standard mathematical polynomial. GeckoLib 4
sampling behavior is referenced in animation comments; no GeckoLib binary is bundled.

The optional AX 2.6.72 pose adapter uses integration identifiers and binary fingerprints
to read matrices during the installed renderer's own draw. No AX classes, bytecode,
models, textures or decompilation output are distributed. Animation playback remains
inside AX; this mod no longer replays its runtime animation clock.
