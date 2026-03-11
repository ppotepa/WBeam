# Training Domain

This domain owns autotune/training workflows for WBeam.

## Entry points

- `wizard.py` - interactive TUI for per-device training (`./wbeam train wizard`)
- `train_max_quality.sh` - two-stage high-quality training helper

## Data outputs

- `config/training/profiles.json` - exported runtime profiles (source of truth)
- `config/training/autotune-best.json` - latest best trial config
- `logs/train/` - generated training result reports

Legacy `proto` autotune path is retired. Training is owned only by `wizard.py` flow.
