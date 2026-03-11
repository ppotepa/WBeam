# HUD Maquettes

Canonical HUD Source of Truth files:

- `docs/ui/hud/trainer-hud.sot.svg`
- `docs/ui/hud/runtime-hud.sot.svg`

Rules:

1. `trainer-hud.sot.svg` is the only layout source-of-truth for Android trainer HUD.
2. `runtime-hud.sot.svg` is the only layout source-of-truth for Android runtime HUD (normal replication mode).
3. Any HUD implementation change must be aligned with the corresponding maquette first.
4. Field mapping legend embedded in each SVG is authoritative for data keys and fallbacks.
5. Runtime HUD must not render trainer fields (trial/generation/progress/score) and must stay fullscreen.
