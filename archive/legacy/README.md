# Legacy Archive

This directory stores historical R&D lanes that are not part of the main production runtime path.

Contents:

- `archive/legacy/proto` -> former `proto/` sandbox
- `archive/legacy/proto_x11` -> former `proto_x11/` sandbox

Compatibility aliases are temporarily kept at repository root:

- `proto` -> `archive/legacy/proto`
- `proto_x11` -> `archive/legacy/proto_x11`

These aliases should be removed only after all external tooling and docs stop referring to root-level legacy paths.
