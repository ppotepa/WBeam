# WBeam Wayland Portal Restore Token Investigation

This document tracks the follow-up work for replacing the manual approval asset with a restore-token-aware portal flow.

## Goal
Determine whether the GNOME ScreenCast portal returns a reusable restore token for the virtual monitor source and whether WBeam can persist and reuse it across runs.

## Checklist
- Confirm where the host daemon handles the portal response.
- Capture portal response fields in debug logs.
- Check whether a restore token is returned on Fedora 43 GNOME Wayland virtual monitor capture.
- Persist the token in a user config file if the backend supports reuse.
- Feed the token back into the portal start flow on the next run.
- Report whether a restore token was present in `stream_smoke` summaries.

## Likely implementation path
- inspect the host Rust code that opens the portal session
- add debug logging for the portal response payload
- store token state under `~/.config/wbeam/portal-restore-token.json`
- make token reuse optional and backward compatible

## Scope
This is not required for Batch 006 to pass.
Batch 006 passes when:
- blocked runs are classified as `portal_consent_required`
- manual portal approval can be captured into `gnome-wayland-portal-consented.qcow2`

Restore-token support can be added later if it requires host/runtime changes.
