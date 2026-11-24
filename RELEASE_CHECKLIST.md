# Google Play Release Checklist

Standalone pre-release checklist before publishing OffGrid Maps to Google Play.

## Security & Secrets
- [ ] `MAPS_GITHUB_TOKEN` cleared from `local.properties` / release build environment (`BuildConfig.MAPS_GITHUB_TOKEN` must be empty in public AAB).
- [ ] `offgrid-maps-data` switched to **Public** or migrated to Cloudflare R2 CDN (see [MIGRATION.md](./MIGRATION.md)).
- [ ] `OfflineRegionHelper` uses direct static download URLs (`releases/download/` or CDN) instead of unauthenticated `api.github.com` lookups.
- [ ] `network_security_config.xml` restricts cleartext traffic strictly to `127.0.0.1` / `localhost`.

## Map Data & Catalog Verification
- [ ] `scripts/validate_map_packages.py --require-all` passes with **0 errors** across all 476 global regions.
- [ ] All 6 continent release archives (`world-eu`, `world-na`, `world-sa`, `world-as`, `world-af`, `world-oc`) verified accessible without authentication.
- [ ] `app/src/main/assets/default_catalog.json` matches latest `catalog.json` from `offgrid-maps-data`.

## App Build, UI & QA
- [ ] `./gradlew test` passes (`0` failures, `0` skipped).
- [ ] Release AAB builds cleanly with `minifyEnabled = true` and ProGuard/R8 rules verified on real Wear OS hardware (offline map render, trail layers, Survival POIs, GPX track recording).
- [ ] App UI, map labels (`name:en` → `name`), and Play Store listing metadata verified 100% in English.
- [ ] Permissions (`ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `BODY_SENSORS` if used) and privacy policy prepared for Google Play Console Data Safety form.

## Studio Account & Domain Notes
- **Publisher / Developer Google Account:** `appmanufacture.dev@gmail.com`
- **Available Domain Options (checked via RDAP / WHOIS on 2026-11-24):**
  - `appmanufacture.dev` (available) — recommended studio domain for Privacy Policy hosting, Cloudflare R2 CDN (`maps.appmanufacture.dev`), and free per-app Cloudflare Email Routing aliases (`offgridmaps@appmanufacture.dev`).
  - `appmanufacture.cz`, `offgridmaps.app`, `offgridmaps.dev` (also available at time of check).
