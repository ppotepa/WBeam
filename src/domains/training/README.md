# Training Domain

This domain owns autotune/training workflows for WBeam.

## Entry points

- `wizard.py` - interactive TUI for per-device training (`./wbeam train wizard`)
- `legacy_engine.py` - dynamic autotune engine (single portal consent + live HUD)
- `train_max_quality.sh` - two-stage high-quality training helper

## Data outputs

- `config/training/profiles.json` - exported runtime profiles (source of truth)
- `config/training/autotune-best.json` - latest best trial config
- `logs/train/` - generated training result reports

## Compatibility wrappers

Legacy paths under `proto/` still exist as thin wrappers:

- `proto/autotune.py`
- `proto/train-autotune-max-quality.sh`

They forward execution to this domain to keep older commands/scripts working.
