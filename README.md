# OffGrid Maps (Wear OS)

Outdoorová offline mapová aplikace pro hodinky se systémem Wear OS zaměřená na maximální výdrž baterie, spolehlivou navigaci v terénu a intuitivní ovládání.

## Klíčové vlastnosti
- **100% Offline navigace:** Vektorové mapové podklady (MBTiles) uložené přímo v paměti hodinek.
- **Šetrnost k baterii:**
  - Optimalizovaný True Black AMOLED styl (černé pixely = 0 mW).
  - Ambient Mode (Always-On) s nízkofrekvenčním překreslováním.
  - Adaptivní vzorkování GPS (prodloužené intervaly při chůzi, usínání při stání).
  - Render-on-demand (vykreslování pouze při změně pohledu).
- **Ergonomie v terénu:**
  - Podpora fyzické otočné korunky / lunety (Rotary Input) pro plynulý zoom bez zakrývání displeje prsty.
  - Zvýrazněné pásové turistické značení (KČT), vrstevnice a rozcestníky.
  - Haptická odezva (haptic feedback) při krokování zoomu a varování při opuštění trasy.

## Technický stack
- **Jazyk:** Kotlin
- **UI:** Jetpack Compose for Wear OS (Material 3 for Wear OS)
- **Mapový Engine:** MapLibre Native Android
- **Lokalizace:** FusedLocationProviderClient + Foreground Location Service
- **Úložiště:** Room DB / MBTiles (SQLite)
