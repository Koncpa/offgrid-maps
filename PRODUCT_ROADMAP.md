# OffGrid Maps (Wear OS) – Produktová dokumentace & Roadmapa

**OffGrid Maps** je samostatná (standalone) outdoorová mapová a navigační aplikace pro chytré hodinky se systémem **Wear OS** (optimalizovaná a testovaná na **Samsung Galaxy Watch Ultra `SM-L705F`**, plně kompatibilní se všemi hodinkami s Wear OS).

Hlavním cílem produktu je nabídnout v českých horách a lesích **100% spolehlivou offline turistickou mapu se značením KČT, vrstevnicemi a GPX navigací přímo na zápěstí** s extrémně nízkou spotřebou baterie.

---

## 1. Co je již vyvinuto a nasazeno v produkčním jádru

### A) Kartografie & Offline Mapy
- **Vektorové offline mapy podle krajů ČR po vzoru Mapy.com (1-klik stažení):**
  - Předinstalovaný **Jihomoravský kraj** (`z8–z14`) + jedním klepnutím stažitelný katalog všech ostatních **13 krajů České republiky** (*Kraj Vysočina, Jihočeský kraj, Zlínský kraj, Olomoucký kraj, Moravskoslezský kraj, Pardubický kraj, Královéhradecký kraj, Středočeský kraj, Praha a okolí, Plzeňský kraj, Liberecký kraj, Ústecký kraj, Karlovarský kraj*) i možnost stažení libovolného vlastního výřezu mapy.
- **Offline vrstevnice po 10 m a 50 m (`ContourTileManager`):**
  - Web Mercator dlaždicový generátor a trvalá flash cache (`filesDir/contour_tiles/tile_12_{x}_{y}.features`) odvozená z digitálního modelu terénu pomocí algoritmu *Marching Squares*.
- **České turistické značení KČT a mapová hierarchie podle zoomu (`StyleBuilder`):**
  - Od `zoom 9`: Pásové turistické trasy KČT (červená, modrá, zelená, žlutá) a hlavní silnice.
  - Od `zoom 12`: Lesní a polní cesty (`class = track`, dlouhé čárkování) + vedlejší (10 m) a hlavní (50 m) vrstevnice.
  - Od `zoom 13`: Úzké lesní pěšiny (`class = path/footway`, krátké čárkování) a výškové kóty vrstevnic.

### B) Lokalizace, Lesní GNSS & Ortometrický výškoměr
- **Hybridní 10m / Lesní GNSS přijímač (`LiveGpsManager` & `LocationTrackingService`):**
  - V otevřeném terénu primárně využívá nízkoenergetický Bluetooth fix z telefonu ($\le 10\text{ m}$).
  - Při aktivním treku (`REC`), stisku tlačítka `GPS` nebo v lese (kde je telefon stíněný v batohu) okamžitě (`0 ms`) zapíná přímý hardwarový satelitní čip v hodinkách (`LocationManager.GPS_PROVIDER`) a drží nepřetržitý satelitní zámek bez cyklického vypínání.
- **Barometrický a satelitní výškoměr s korekcí geoidu EGM2008 (`BarometricAltimeterManager`):**
  - Automaticky převádí elipsoidickou výšku Androidu ($h_{\text{WGS84}}$) na skutečnou ortometrickou nadmořskou výšku ($H_{\text{MSL}}$ / Bpv) odečtením undulace geoidu EGM2008 ($+43\text{ až }+47\text{ m}$ v ČR, např. $+44,56\text{ m}$ na jižní Moravě).
  - Okamžitá kalibrace barometru při startu (i uvnitř budovy z posledního známého 3D fixu) a perzistence kalibračního posunu v `SharedPreferences`.

### C) GPX Navigace & Záznam trasy
- **Asynchronní import, export a správa GPX tras (`GpxExporter`):**
  - Podpora souborů z Mapy.cz (`<rtept>` i `<trkpt>`), načítání velkých tras (3 000+ bodů) na pozadí přes `Dispatchers.IO`, možnost zrušení aktivní trasy (*Zrušit trasu*) bez smazání souboru, sdílení do `Download/OffGridMaps` a trvalé mazání.
- **Kolmá vzdálenost k úsečce trasy (Cross-Track / Point-to-Segment Distance):**
  - Kontrola odchylky od naplánované GPX trasy ($> 60\text{ m}$) počítá kolmou projekci na každou úsečku trasy (nikoliv jen vzdálenost k vrcholům), čímž eliminuje falešné poplachy na dlouhých rovných lesních cestách.
- **Záchrana trasy při vybití baterie:**
  - Průběžné ukládání záchranného checkpointu a automatické nouzové uložení GPX při poklesu baterie na $\le 5\text{ %}$.

### D) Ultra-Pure AMOLED Energetická Optimalizace
- **Zero-Blue paleta:** Čistě černé pozadí `#000000` (`0 mW`) a jantarově oranžová naplánovaná trasa `#E66800` s nulovou modrou složkou (`B = 0`), která šetří přes 52 % energie displeje oproti bílé barvě.

---

## 2. Produktový Backlog – Nápady pro další vývoj (Roadmapa)

Níže jsou sepsány funkční celky připravené pro budoucí iterace produktu:

### 1. Statistiky GPX tras (`km`, `↑ m`) a zbývající vzdálenost do cíle
- **Popis:**
  - V záložce `Trasy` u každé uložené GPX trasy spočítat a zobrazit její celkovou délku a nastoupané metry (např. **`28,4 km • ↑640 m`**).
  - Při aktivní navigaci po GPX trase zobrazit, **kolik kilometrů a výškových metrů ještě zbývá do cíle** (případně odhadovaný čas příchodu ETA podle aktuálního tempa chůze).
- **Technické řešení:**
  - Při prvním načtení seznamu GPX souborů spočítat na `Dispatchers.IO` délku a kumulativní převýšení s hysterezí a uložit metadata do lehké lokální cache.
  - Při chůzi po trase promítnout aktuální polohu na nejbližší segment trasy $i$ a sečíst délku zbývajících segmentů $i \dots N$.

### 2. Rychlý záchytný bod „Auto / Chata / Start“ (Waypoint & Azimut návratu)
- **Popis:**
  - Při pohybu mimo značené cesty (např. houbaření v lese, volný horský terén, hledání zaparkovaného auta nebo kempu) umožnit jedním stiskem uložit aktuální polohu jako **📍 Auto / Start**.
  - Na mapě vykreslit výrazný bod a přerušovanou spojnici od aktuální polohy k uloženému bodu včetně přímé vzdušné vzdálenosti a azimutu.
- **Technické řešení:**
  - Dlouhý stisk tlačítka `GPS` (nebo tlačítko v menu) uloží souřadnice do `SharedPreferences` a přidá `GeoJsonSource` vrstvu se symbolem a spojnicí k aktuální GPS poloze.

### 3. Ovládání bočním / akčním tlačítkem (do deště a zimních rukavic)
- **Popis:**
  - V dešti, sněhu nebo v silných rukavicích je ovládání dotykového displeje obtížné.
  - Namapovat hardwarová boční tlačítka hodinek (`KEYCODE_STEM_PRIMARY`, `KEYCODE_STEM_1`, akční tlačítko Galaxy Watch Ultra) pro rychlé akce:
    - **Krátký stisk:** Okamžité vycentrování mapy na aktuální GPS polohu a uzamčení sledování.
    - **Dvojstisk / dlouhý stisk:** Přepnutí měřítka (zoom in / zoom out) nebo uložení záchytného bodu.

### 4. Ultra-úsporný Ambient režim (Always-On Display pro chůzi s holemi)
- **Popis:**
  - Když má turista v obou rukách trekové hole nebo stoupá do prudkého svahu, nechce kvůli kontrole křižovatky pokaždé zvedat ruku.
  - V Ambient režimu (`AmbientLifecycleObserver`) ponechat na displeji pouze obrysovou 1-bitovou křivku GPX trasy, šipku polohy a nejbližší křižovatku (skrytá ovládací tlačítka, snížený jas, překreslení jen jednou za 30 sekund).

### 5. Výškový profil trasy na displeji hodinek
- **Popis:**
  - Po klepnutí na údaj nadmořské výšky (`▲ 368 m n.m.`) v horní pilulce zobrazit kompaktní graf výškového profilu aktivní GPX trasy s vyznačenou tečkou, kde se turista na profilu právě nachází (kolik kopců má za sebou a jaké stoupání je před ním).
