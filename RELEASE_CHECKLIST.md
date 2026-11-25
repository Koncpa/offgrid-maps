# Google Play Release Checklist

Standalone pre-release checklist before publishing OffGrid Maps to Google Play.

## Security & Secrets
- [x] `MAPS_GITHUB_TOKEN` cleared from `local.properties` (`BuildConfig.MAPS_GITHUB_TOKEN` is empty in public AAB).
- [x] Offline downloads migrated to Cloudflare R2 CDN (`https://offgrid-maps-cdn.appmanufacture-dev.workers.dev`), removing `api.github.com` token requirements for end users *(optional: switch `Koncpa/offgrid-maps-data` from `PRIVATE` to `Public` for unauthenticated GitHub Releases fallback)*.
- [x] `OfflineRegionHelper` uses direct CDN/release archive URLs (`resolveCdnZipUrl`) instead of unauthenticated `api.github.com` lookups.
- [x] `network_security_config.xml` restricts cleartext traffic strictly to `127.0.0.1` / `localhost`.
- [x] Production upload keystore (`.secrets-upload-keystore.jks`) generated and wired into `signingConfigs["release"]` via `local.properties` (`versionCode = 1`, `versionName = "1.0.0"`).

## Map Data & Catalog Verification
- [x] `scripts/validate_map_packages.py --require-all --verify-osm` passes with **0 errors** across all 476 global regions.
- [x] All 6 continent release archives (`world-eu`, `world-na`, `world-sa`, `world-as`, `world-af`, `world-oc`) and `catalog.json` verified HTTP 200 on Cloudflare R2 Workers CDN.
- [x] `app/src/main/assets/default_catalog.json` matches latest `catalog.json` v5 from `offgrid-maps-data`.

## App Build, UI & QA
- [x] `./gradlew test` passes (`40` tests, `0` failures, `0` skipped).
- [x] Release AAB (`app/build/outputs/bundle/release/app-release.aab`) builds cleanly with `minifyEnabled = true` and production upload signature (`CN=AppManufacture, OU=OffGridMaps`).
- [ ] Final features verified on real Wear OS hardware (Wi-Fi download lock, GPX routes).
- [x] App UI, map labels (`name:en` → `name`), and documentation verified in English.
- [ ] Re-upload updated `docs/` (`index.html`, `privacy.html`) to Cloudflare Pages (`https://offgridmaps.pages.dev/`).
- [ ] Prepare Play Store Wear OS screenshots (`1:1`), `512×512` icon, `1024×500` graphic, Data Safety form, and upload `app-release.aab` in Google Play Console.

## Studio Account, Live Cloudflare Endpoints & Domain Notes
- **Publisher / Developer Google Account:** `appmanufacture.dev@gmail.com`
- **Privacy Policy URL (Cloudflare Pages):** `https://offgridmaps.pages.dev/privacy` (Home: `https://offgridmaps.pages.dev/`)
- **Map CDN URL (Cloudflare Workers → R2 `offgridmapsdata`):** `https://offgrid-maps-cdn.appmanufacture-dev.workers.dev`
- **Optional Future Custom Domain Notes (checked via RDAP / WHOIS on 2026-11-24):**
  - `appmanufacture.dev` (available) — optional studio domain for custom-domain CDN (`maps.appmanufacture.dev`), branded Privacy Policy, and Cloudflare Email Routing aliases (`offgridmaps@appmanufacture.dev`).
  - `appmanufacture.cz`, `offgridmaps.app`, `offgridmaps.dev` (also available at time of check).
