# FixMeAwesome - Roadmap

**Purpose:** walk, get pinged when you are near an open OSM Note or a `fixme`-tagged object, and deal with it on the spot: notes in the app, map data through an external editor, or capture evidence for later.

Decided 2026-09-22 with the maintainer. Replaces the earlier 300-item "Survey Me" list; the completed phases from that list are kept below as history. The documents in `design/` predate this pivot and describe the older, broader "Survey Me" concept; treat them as background, not as requirements.

## Audience

Public release eventually (Play Store and F-Droid are the stated channels). Until then the maintainer is the only user, so polish items sit after the core loop.

## The loop

1. The app knows where you are, even in the background.
2. It fetches open notes and fixmes around you, and refetches as you move.
3. It pings you when you are close to one.
4. Tapping the ping opens that item.
5. Notes: read the thread, comment, close, reopen, or create a new one, all in-app with your OSM account.
6. Fixmes: hand off to an external editor that changes map data. This app does not edit map data.
7. Either: capture a photo or text pinned to the point for review at home.

## Built (history, condensed)

- Single-activity app with bottom navigation: Home, Map, Tracks, Settings, Notes.
- osmdroid map with pan, zoom, tile cache, user location dot and accuracy circle.
- Location permissions including background location and Android 13 notifications.
- Foreground tracking service with wake lock and stop action in its notification.
- Track recording: polyline on the map, distance and duration, saved to Room, listed and deletable in the Tracks tab. Naming, GPX export and colour coding were never done.
- Room database with POI and track tables, migrations and a backup utility.
- POI model with categories, GPX waypoint import, category icons on the map.
- Proximity detection with per-POI cooldown, visited flag, and a high-priority notification that names the POI and its distance. Tapping it opens the app, not the item.
- Overpass queries: bike share stations (Toronto sample) and `fixme`-tagged nodes and ways within 2 km.
- OSM Notes API: open notes within 2 km, parsed into POIs. Note id and status survive in the tags map; the comment thread is flattened into one text field.
- Fetching happens on map load (notes only, around the last map position) and from a Settings button (notes plus fixmes). It does not follow the walker yet.
- Notes tab: unified list of notes and fixmes sorted by distance, type tag, details dialog, and round-trip to the map marker. **Known bug:** fixmes are saved with source `overpass` but the tab's query only selects `osm_notes` and `osm_fixme`, so fixmes never appear in the list.
- PowerShell GPS simulators and build-and-install scripts.

## Next, in order

Each item is one session-sized piece of work. Ticks go here; do not renumber.

0. [ ] **Fixmes actually reach the list.** Save fixme POIs with source `osm_fixme` (or widen the query) and drop the old `overpass` rows. Small, and nothing after it is testable without it.
1. [ ] **Ping opens the item.** The proximity notification deep-links to the note or fixme details, not to MainActivity. Include a mute-this-one action.
2. [ ] **Fetch follows the walker.** Refetch notes and fixmes when the user has moved more than half the fetched radius from the last fetch centre, from the tracking service, not only from the map or Settings. Expire items outside the radius. Respect Overpass and osm.org rate limits.
3. [ ] **First-class note model.** Store OSM note id, status, creation date and the comment thread as structured data instead of a flattened text field. Keep closed notes so a note you just closed can still be shown.
4. [ ] **Note details rework.** Thread rendered as comments with author and date, status shown, link to osm.org.
5. [ ] **OSM login (OAuth 2.0 with PKCE).** Register an OAuth 2 client at osm.org, scope `write_notes`. Store the token in EncryptedSharedPreferences. Settings shows who is logged in and a logout. OSM only supports OAuth 2.0; the redirect must be HTTPS or a native scheme, confirm the native-scheme option during client registration.
6. [ ] **Comment, close, reopen a note** from the details view via `POST /api/0.6/notes/{id}/comment|close|reopen`. Optimistic update, retry queue when offline.
7. [ ] **Create a note** where you stand, or at a long-press on the map. Creation is anonymous-capable in the API but should use the logged-in user when available.
8. [ ] **Fixme hand-off.** From fixme details: open the element in an external editor. Start with an osm.org element URL and a `geo:` intent; then editor-specific intents (StreetComplete, Vespucci) once verified on a device. Keep the OSM element type and id as structured fields; today they only live inside the POI id string.
9. [ ] **Evidence capture.** Photo or text pinned to a note or fixme, stored locally, listed in a "to review" view. No upload; this is for the desk session later.
10. [ ] **Fixme list quality.** Show the `fixme` text and the element's main tags in the list; filter out fixmes on relations or those older than a chosen age; dismiss-for-now.
11. [ ] **Settings for the loop.** Fetch radius, ping radius, mute notes, mute fixmes, quiet hours.
12. [ ] **Battery pass.** Location update interval adapts to speed; fetches batch behind the location cadence; measure a two-hour walk.
13. [ ] **Rename.** App name, package label and docs from "Survey Me" to FixMeAwesome (package id stays `com.surveyme` unless a release is far off enough to change it).
14. [ ] **Release prep.** Adaptive icon, privacy policy (location, OSM account), ProGuard rules, signed build, listing text and screenshots.
15. [ ] **Tests around the loop.** Unit tests for the notes API client, the refetch trigger, and proximity logic. One instrumented test for the deep-link.

## Parked

Cut from the old list on purpose. Revisit only if the loop is done and a user asks.

- Offline map region download.
- Cloud sync, accounts beyond OSM login, conflict resolution.
- Track analysis: elevation profiles, speed graphs, splitting, merging, heatmaps, replay.
- Advanced GPX: routes, extensions, compressed files, batch import.
- Static GPX POI lists as a primary input. Import stays as-is but is not developed further.
- Bike share station queries (were a test of Overpass, not a feature).
- ViewModel, repository and coroutine "migration" phases. Do them where a feature touches the code, not as projects.
- Analytics, Crashlytics, rating prompts, onboarding tutorial.

## Open decisions

- **License.** `origin/main` carries an MIT LICENSE added 2026-06-05; CLAUDE.md says GPL-3.0. Must be one or the other before the eleven local commits are pushed.
- **F-Droid vs Play Services.** The app uses Google Play Services location, which F-Droid will not build. Either drop the F-Droid channel or switch to the platform LocationManager (which the Notes tab already uses for distance). Decide before item 12.
- **Package id.** `com.surveyme` is baked into the manifest and any existing installs. Changing it means a fresh install for everyone; fine now, costly after release.
