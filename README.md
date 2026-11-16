# OffGrid Maps (Wear OS)

Outdoorová offline mapová aplikace pro hodinky se systémem **Wear OS** zaměřená na maximální výdrž baterie, spolehlivou navigaci v českém terénu a intuitivní ovládání.

Detailní popis architektury a plán dalšího rozvoje (backlog) najdeš v souboru **[PRODUCT_ROADMAP.md](./PRODUCT_ROADMAP.md)**.

## Klíčové vlastnosti
- **100% Offline navigace & Katalog oblastí ČR (1-klik stažení):**
  - Předinstalovaný Jihomoravský kraj + vestavěný katalog 12 hlavních pohoří a turistických oblastí ČR (*Krkonoše, Šumava, Beskydy, Jeseníky, Vysočina, Český ráj, České Švýcarsko, Orlické hory, Praha a okolí, Brdy, Krušné hory, Podyjí*) pro okamžité stažení jedním klepnutím včetně vlastního výřezu mapy.
- **Offline vrstevnice (10 m / 50 m) a české značení KČT:**
  - Web Mercator dlaždicová cache vrstevnic a kompletní vrstvy turistických tras KČT (červená, modrá, zelená, žlutá), lesních cest a pěšin podle kartografických konvencí.
- **Blesková GPS v lese & Ortometrický výškoměr (EGM2008):**
  - Hybridní příjem (úsporný Bluetooth fix z telefonu v otevřeném terénu + okamžitý přímý zámek hardwarového GNSS čipu hodinek v lese a při aktivním treku).
  - Barometrický a satelitní výškoměr s korekcí geoidu EGM2008 (`▲ m n.m.`), který eliminuje ~45m elipsoidickou odchylku WGS84 v ČR.
- **GPX Navigace & Záznam trasy:**
  - Import/export `.gpx` tras (včetně tras z Mapy.cz), výpočet kolmé vzdálenosti k úsečce trasy (*Cross-Track Distance*), haptické varování při opuštění trasy (>60 m) a nouzové uložení při 5 % baterie.
- **Extrémní šetrnost k baterii (Ultra-Pure AMOLED):**
  - Čistě černé pozadí (`#000000` = `0 mW`) a jantarová křivka naplánované trasy s nulovou modrou složkou (`#E66800`, >52% úspora energie displeje).

## Technický stack
- **Jazyk:** Kotlin (Coroutines, StateFlow)
- **UI:** Jetpack Compose for Wear OS
- **Mapový Engine:** MapLibre Native Android + lokální loopback tile/style server
- **Lokalizace & Senzory:** `FusedLocationProviderClient` + přímý `LocationManager.GPS_PROVIDER`, `Sensor.TYPE_PRESSURE` (barometr s korekcí EGM2008), `Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR` (kompas)
- **Testy:** JUnit 4 sada unit testů (`./gradlew test`)
