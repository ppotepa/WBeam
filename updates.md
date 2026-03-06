# WBeam Updates Log

Date: 2026-03-06

## [1] Start porzadkow repo + single-entrypoint policy
- Cel: zostawic tylko dwa glowne skrypty w root: `devtool` i `runas-remote`.
- Kontekst: `devtool` ma przejac role dawnych wrapperow (`wbeam`, `wbgui`, `desktop*.sh`, deploy/upload helpery).
- Referencja stabilnej bazy do porownan: commit `f1472156ff4e073a6568e5c1a907e0080135d8e7`.

## [2] Devtool rozszerzony o `deps install`
- Powod: zachowanie funkcji `install-deps` bez dodatkowego skryptu root.
- Zmiana: `./devtool deps install` wykonuje instalacje pakietow (Arch) i SDK Android oraz health-check.
- Efekt: mozna usunac `install-deps` z root bez utraty funkcjonalnosci.
