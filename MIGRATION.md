# Future Hosting Migration Guide

How to move pre-built global map packages (`Koncpa/offgrid-maps-data`) from the current private beta setup to public hosting or dedicated CDN storage without regenerating map data.

---

## Option A: Private GitHub → Public GitHub (~5 min, $0)

Best for open beta or indie release up to several thousand active users.

1. Switch `Koncpa/offgrid-maps-data` visibility to **Public** (**Settings → Danger Zone → Change repository visibility**).
2. Remove `MAPS_GITHUB_TOKEN` from `local.properties` / CI build secrets so no GitHub token ships inside the APK/AAB.
3. Update `downloadReleaseZipPackage(...)` in `OfflineRegionHelper.kt` to use the direct Release asset URL when `BuildConfig.MAPS_GITHUB_TOKEN` is blank (avoids the 60 req/hr unauthenticated `api.github.com` limit):
   ```kotlin
   val directUrl = "https://github.com/$repo/releases/download/$releaseTag/$assetName"
   ```
4. `catalog.json` continues syncing directly from `https://raw.githubusercontent.com/$repo/main/catalog.json`.

---

## Option B: GitHub Releases → Cloudflare R2 / CDN (~20–30 min, $0 egress)

Recommended when scaling beyond ~3,000 active users or to decouple storage URLs from future app updates.

1. Create a public **Cloudflare R2** bucket (`offgrid-maps-data`) with a custom domain (e.g. `https://maps-cdn.yourdomain.com`).
2. Upload the already-built `.cache/releases/` ZIPs and `catalog.json` directly (no map rebuild needed):
   ```bash
   rclone copy catalog.json r2:offgrid-maps-data/
   rclone copy .cache/releases r2:offgrid-maps-data/releases --transfers=16
   ```
3. Update `OfflineRegionHelper.kt` to support an optional `downloadUrl` / `releaseBaseUrl` field from `catalog.json` and point `syncCatalogFromGithub()` at the CDN `catalog.json` URL (future backend changes then require editing only `catalog.json`, with no app update).
4. Optional — move base vector tiles off OpenFreeMap (`tiles.openfreemap.org`) by uploading a planet `.pmtiles` archive to the same R2 bucket and configuring `vectorTilesUrl` in `catalog.json`.
