# CURRENT - PROTO

Data: 2026-02-26
Obszar: tylko projekt `proto` (z uwzglednieniem integracji z host-streamingiem, bo to wplywa na FPS).

## Co robimy teraz
Aktualnie pracujemy nad **responsywnoscia aplikacji**:
- problem zgloszony: bardzo niski klatkaz (~3 FPS), mimo poprawnej jakosci obrazu,
- cel na teraz: znalezc i usunac najdrozsze miejsca w pipeline, tak aby domyslny `run.sh` uruchamial tryb bardziej "interactive", nie tylko "ogladowy".

## Co juz zrobilem (konkretnie) i po co
1. Naprawa stabilnosci Android/API17 (blad `IllegalStateException`):
- utwardzenie sciezki `H264Transport` (kolejnosc configure/start/drain, defensywne queue config, zgodnosc z API17),
- cel: usunac crash i przywrocic stabilne uruchamianie.

2. Dokumentacja architektury:
- uzupelniony plik `struct.md` o opis prototypu i sekcje analizy wydajnosci,
- cel: miec jedno zrodlo prawdy do dalszych optymalizacji.

3. Optymalizacje pod responsywnosc (domyslny start):
- zmiany w `proto/config/proto.conf` (preset `fast`, flagi pod szybszy przeplyw klatek),
- zmiany w `proto/run.sh` (przekazywanie i logowanie nowych flag runtime),
- strojenie po stronie host stream (`stream_wayland_portal_h264.py`), bo to byl glowny bottleneck doplywu klatek,
- cel: zwiekszyc realny FPS i ograniczyc "zawieszanie" starej klatki.

4. Weryfikacja po zmianach:
- `bash -n run.sh` OK,
- kompilacja Android (`:app:compileDebugJavaWithJavac`) OK,
- testy runtime: w udanym przebiegu ~24-29 FPS; nadal wystepuja incydentalne problemy startu zrodla portal/PipeWire.

5. Korekta pod lekka regresje FPS (dzisiaj):
- ograniczenie "stale duplicate burst" w `host/scripts/stream_wayland_portal_h264.py` (duplikaty sa teraz pace'owane do docelowego FPS zamiast wysylki >100 FPS),
- przekazanie brakujacych flag `WBEAM_PIPEWIRE_*` i `WBEAM_FRAMED_PULL_TIMEOUT_MS` z `proto/host/src/main.rs` do procesu streamera,
- strojenie domyslne w `proto/config/proto.conf`: `WBEAM_VIDEORATE_DROP_ONLY=1`, `WBEAM_PIPEWIRE_ALWAYS_COPY=0`, `WBEAM_FRAMED_PULL_TIMEOUT_MS=20`.
- cel: zmniejszyc przeciazenie dekodera i odzyskac stabilny realny FPS.

6. Korekta pod miganie/powtorki klatek (virtual desktop, biezacy run):
- przejscie na profil bez stale-duplicate po stronie framed sender (`WBEAM_FRAMED_DUPLICATE_STALE=0`),
- przeniesienie duplikacji na etap `videorate` (`WBEAM_VIDEORATE_DROP_ONLY=0`) zamiast powtarzania stalego keyframe,
- podniesienie stabilnosci source (`WBEAM_PIPEWIRE_ALWAYS_COPY=1`, `WBEAM_FRAMED_PULL_TIMEOUT_MS=33`).
- cel: ograniczyc miganie i "powarzanie" tej samej klatki przy rzadkich aktualizacjach PipeWire.

7. Diagnostyka zwisu `run.sh` po wyborze SERIAL:
- dodany timeout dla wczesnego `adb shell am force-stop` (`PROTO_ADB_SHELL_TIMEOUT_SECS`, domyslnie 8s),
- dodany czytelny log preflight i warning zamiast cichego wiszenia.
- cel: startup nie blokuje sie bez informacji, nawet gdy ADB chwilowo nie odpowiada.

8. Redukcja opoznienia sterowania (target: <100 ms):
- w `stream_wayland_portal_h264.py` dodane odrzucanie backlogu appsink (wysylka tylko najnowszej probki),
- zmniejszone domyslne kolejki GStreamer pod low-latency (`WBEAM_QUEUE_MAX_BUFFERS=1`, `WBEAM_QUEUE_MAX_TIME_MS=12`, `WBEAM_APPSINK_MAX_BUFFERS=2`),
- nowe pokretla przeprowadzone przez caly lancuch `proto.conf -> run.sh -> proto/host/src/main.rs -> env python`.
- cel: ograniczyc buforowanie klatek i skrócic lag reakcji.

## Jaki jest cel zadania
Glowny cel zadania:
- **stabilna i responsywna aplikacja w prototypie**, kompatybilna z legacy Android API17+, z domyslnym profilem startowym nadajacym sie do realnej interakcji (klikniecia, sterowanie), a nie tylko pasywnego podgladu obrazu.

## Co dalej (bezposrednio)
1. Dalsze profilowanie "end-to-end" (host -> transport -> dekoder -> render) i pomiar opoznien na etapach.
2. Domkniecie niestabilnych przypadkow startu zrodla (portal/PipeWire), zeby startup byl powtarzalny.
3. Dalsze strojenie flag domyslnych pod balans: latency vs CPU vs stabilnosc.

## Aktualizacja 2026-02-27 - autotune (5 generacji)
- Uruchomiony pelny tuning ewolucyjny (`generations=5`, `population=8`, `elite=2`, `mutation=0.35`, `warmup=12s`, `sample=24s`).
- Kluczowa obserwacja: sam wysoki FPS nie wystarcza; czesc profili miala bardzo dobre `sender_p50`, ale wysoki `timeout_misses` (duzy lag i niestabilnosc).
- Najlepszy wynik surowy z runu: `g03_05_child5` (`sender_p50=56.5`, ale `timeout_mean=111.7`) - to kandydat szybki, ale ryzykowny interakcyjnie.
- Profile zblizone FPS (~50) i niskim timeout (`~10-15`) okazaly sie praktycznie lepsze dla responsywnosci.

Wprowadzona korekta autotunera:
- zmieniony scoring w `proto/autotune.py`: dodatkowa, progowa kara za wysokie `timeout_misses` + kara za jitter (`sender_p50 - sender_p20`),
- ranking i logi rozszerzone o `tpen` (timeout penalty) i `jitter`,
- cel: wybierac ustawienia stabilne interakcyjnie, a nie tylko maksymalny peak FPS.
