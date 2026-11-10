# Shared Protocol

[`protocol/rust/`](protocol/rust/Cargo.toml) is the WBTP workspace:

| Crate | Role |
|---|---|
| `wbtp-core` | Frame headers, flags, serialization and optional CRC |
| `wbtp-sender` | Sender library and synthetic traffic CLI |
| `wbtp-receiver-null` | Receiver CLI for transport/soak testing |
| `wbtp-host` | Reusable host-side protocol support |

[`protocol/scripts/`](protocol/scripts/) contains the loopback soak helper.
These tools exercise protocol behavior; they do not validate Android decoding
or a physical USB display session.

The production streamer uses `wbtp-core` constants and its own packetizer; the
Android implementation is Java. The
[wire and telemetry contract](../docs/telemetry_schema.md) distinguishes their
live format from optional features in the shared crate. HTTP API models live in
[`wbeamd-api`](../host/rust/crates/wbeamd-api/src/lib.rs).

From the repository root, run protocol unit tests with:

```bash
cargo test --manifest-path shared/protocol/rust/Cargo.toml -p wbtp-core
```

Keep reusable framing independent of UI and host session policy. See the
[architecture map](../docs/repo-structure.md) for ownership boundaries.
