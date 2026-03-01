# API17 Transport Policy

- Keep write timeout conservative (>= 35 ms).
- Prefer latest-frame-wins behavior under pressure.
- Avoid large in-flight buffering on both host and client.
