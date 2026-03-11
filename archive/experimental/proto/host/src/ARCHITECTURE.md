# Host Architecture (target)

This is an incremental SRP/SOLID migration scaffold.
Current runtime still lives in `main.rs`, while new abstractions are introduced here.

## Layers

- `domain/`: core stream data (`Frame`, `StreamSession`, `RuntimeProfile`)
- `application/ports/`: interfaces for capture/codec/transport/profile repository
- `application/orchestrator.rs`: coordinates pipeline (`capture -> codec -> transport`)
- `infrastructure/capture/linux/*`: Linux backend adapters (`portal`, `kms`, `grim`, `import`)
- `infrastructure/capture/win/*`: Windows backend adapters (`dxgi`, `gdi`, `windows_graphics_capture`)

## Migration rule

- No direct ffmpeg/adb/system command usage in `application/*`
- OS-specific behavior must stay in `infrastructure/capture/<os>/<backend>/`
- Keep profile naming stable: `<BACKEND>_<FPS>FPS_<TIER>`
