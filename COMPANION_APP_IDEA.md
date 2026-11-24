# Product Idea: Paid Companion Mobile App (GPX Manager + Route Library)

## Core Concept
A companion Android mobile app paired with the free Wear OS map app. The smartwatch app stays **100% free** as a zero-friction entry point, while the mobile app solves comfortable route management and adds a community route library — offered for a **single one-time app purchase** (individual routes are never sold separately; no subscription).

---

## Key Capabilities

### 1. Personal GPX Library & Watch Transfer
- Fast GPX import from anywhere via the system **Share** sheet (Mapy.com, web browsers, email, files).
- Clean folder organization, elevation profile visualization, and route statistics.
- One-tap transfer of a GPX route (and optionally triggering download of the matching offline map region) to the watch.

### 2. Shared Community Route Library
- Browse and filter routes submitted by other users by **region, distance, and difficulty**, with instant one-tap send to the watch.
- Simple opt-in sharing of personal routes via a single toggle (**"Share with others"**).
- No social network bloat (no user profiles, followers, or comment threads) — strictly clean data: title, short description, region, difficulty, GPX geometry, download count, and thumbs-up rating.

---

## Phased Launch Strategy

1. **Phase 1 — Launch (No Empty-Library Risk):**
   - Release the paid mobile app primarily as a personal GPX manager and watch companion.
   - Pre-populate the **Discover** tab using a static database of official marked hiking routes extracted from OpenStreetMap so the library delivers immediate value from day one with zero complex backend infrastructure.
2. **Phase 2 — Expansion (Community Sharing):**
   - Once the app has a base of paying users, add a lightweight backend for user-submitted GPX uploads and simple thumbs-up/down ranking so the best routes naturally rise to the top.

---

## Technical, Moderation & Business Safeguards

- **Privacy Protection on Shared Routes:** Automatically trim the first and last **~200 meters** of uploaded GPX tracks before publishing to protect the uploader's home address / trailhead parking privacy.
- **Data Quality Without Manual Moderation:** Include a **Report** button, simple like/dislike ranking, and automated speed-anomaly filtering (rejecting tracks where the user forgot to turn off GPS while driving a car).
- **Legal & Tax Simplicity (Czechia):** Since users do not pay for individual routes and no creator payouts/commissions are processed, there is no marketplace intermediary complexity. Secondary sole proprietorship (*vedlejší OSVČ*) + VAT identified-person registration (*identifikovaná osoba k DPH* for Google Ireland) is only needed once the paid mobile app goes on sale.
- **Exit Potential:** A clean separation between a free Wear OS acquisition client and a one-time paid mobile companion app with steady organic sales forms an attractive, acquirable asset (typically valued at 2–4× annual profit on marketplaces like Acquire.com or for strategic outdoor brands).
