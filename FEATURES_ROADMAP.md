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
- Proximity detection with per-POI cooldown, visited flag, and a high-priority notification titled "Note nearby" or "Fixme nearby" with the first line of the text and the distance. Tapping it opens the item in the Notes tab; a "Mute this one" action sets a persisted `isMuted` flag that refetches keep.
- Overpass queries: bike share stations (Toronto sample) and `fixme`-tagged nodes and ways within 2 km.
- OSM Notes API: notes within 2 km (open, plus ones closed in the last 7 days) parsed into an `osm_notes` row (id, status, dates, structured comment thread, reserved `labels` and `surveyAfter`) beside the POI row that carries position and ping flags. A note the API stops returning is dropped unless it was closed within 7 days; closed notes never ping.
- Fetching goes through one gated entry point (`NearbyFetcher`): map load and the Settings button fetch around the last map position, the tracking service refetches around the walker once they are more than half the 2 km radius from the last fetch centre or the last fetch is older than 15 min. Rows outside the radius are expired after each successful fetch. osm.org and Overpass each have a 60 s minimum interval and exponential backoff on failure.
- Notes tab: unified list of notes and fixmes sorted by distance, type tag, details dialog, and round-trip to the map marker. Fixmes are saved with source `osm_fixme` since v1.2.5; earlier builds used `overpass`, and those rows are purged on map load and on fetch.
- PowerShell GPS simulators and build-and-install scripts.

## Next, in order

Each item is one session-sized piece of work. Ticks go here; do not renumber.

0. [x] **Fixmes actually reach the list.** (done 2026-09-24, v1.2.5) Save fixme POIs with source `osm_fixme` (or widen the query) and drop the old `overpass` rows. Small, and nothing after it is testable without it.
1. [x] **Ping opens the item.** (done 2026-09-25, v1.2.6; mute is a persisted `isMuted` column, unmute UI comes with item 13) The proximity notification deep-links to the note or fixme details, not to MainActivity. Include a mute-this-one action. Item 8 later decides which notes are allowed to ping at all.
2. [x] **Fetch follows the walker.** (done 2026-09-25, v1.2.7; one gated `NearbyFetcher` for map, Settings and the service, muted items outside the radius are dropped with their flag) Refetch notes and fixmes when the user has moved more than half the fetched radius from the last fetch centre, from the tracking service, not only from the map or Settings. Expire items outside the radius. Respect Overpass and osm.org rate limits.
3. [x] **First-class note model.** (done 2026-09-25, v1.2.8; `osm_notes` table keyed by note id with a JSON comment thread, `labels` and `surveyAfter`, next to the POI row that stays the thing on the map; closed notes kept 7 days, DB v4) Store OSM note id, status, creation date and the comment thread as structured data instead of a flattened text field. Keep closed notes so a note you just closed can still be shown. Reserve a `labels` set and a nullable `surveyAfter` date on the note now, so item 8 needs no migration.
4. [x] **Note details rework.** (done 2026-09-25, v1.2.9; full-height bottom sheet with a RecyclerView thread, status pill and dates, distance, "Open on osm.org" for notes and for fixmes whose element id is in the POI id, hidden comment/close/reopen row ready for item 6) Thread rendered as comments with author and date, status shown, link to osm.org.
5. [x] **OSM login (OAuth 2.0 with PKCE).** (done 2026-09-25, v1.2.10; client id comes from local.properties, registration is a maintainer step; untested against osm.org) Register an OAuth 2 client at osm.org, scope `write_notes`. Store the token in EncryptedSharedPreferences. Settings shows who is logged in and a logout. OSM only supports OAuth 2.0; the redirect must be HTTPS or a native scheme, confirm the native-scheme option during client registration.
6. [x] **Comment, close, reopen a note** (done 2026-09-25, v1.2.11; actions queue in a `note_actions` table (DB v5) and drain on app start, on connectivity, after login and after each post, oldest first per note, with the server's note replacing the optimistic one; untested against osm.org) from the details view via `POST /api/0.6/notes/{id}/comment|close|reopen`. Optimistic update, retry queue when offline.
7. [x] **Create a note** (done 2026-09-25, v1.2.12; map FAB, map long-press and a Home button open a compose dialog; the note is a `create` row in the same `note_actions` queue (DB v6, lat/lon columns) shown as a muted "pending" placeholder until osm.org answers; anonymous posting allowed after a confirmation when logged out; untested against osm.org) where you stand, or at a long-press on the map. Creation is anonymous-capable in the API but should use the logged-in user when available.
8. [x] **Note classification.** (done 2026-09-25, v1.2.13; `NoteClassifier` is a port of `analysis/classify_notes.py`, the corpus test reproduces its shares: surveyor 59%, telemapper 33%, JOSM 30%; labels recomputed on every store and once for old rows on first launch; only surveyor-class, non-stale, open notes past their `#surveyafter` date ping) Built-in rules only, no user-defined rules yet. Labels are computed locally from note text and metadata and stored on the note. See "Note classes" below for the classes, the evidence, and the strict tag vocabulary. Strict tags written by mappers win over heuristics. Source app is shown as a label but is never a filter. Classes drive: which notes ping (default: surveyor classes only, never future-dated, never stale), list order, marker colour, and a per-audience filter in the list. Prototype and corpus in `analysis/`.
9. [x] **Morning triage.** (done 2026-09-25, v1.2.14; `Triage` screen from a Home card and the Notes toolbar lists open notes created after the `lastTriageAt` stamp, newest first, with the guessed tag pre-filled and `#surveyme` / `#surveyafter DATE` / `#armchair` / `#josm` chips that queue a comment through item 6; Done or the first tag posted stamps the screen's open time, skip and swipe hide for the session, a "show all open notes" switch; untested against osm.org) A "new since last triage" view of notes in the home area, sorted newest first, with one-tap buttons that post the strict tags as comments (`#surveyme`, survey-after with a date picker, and the audience tags). Shows the heuristic guess so the tap is a confirmation. Depends on 6 and 8.
10. [x] **Fixme hand-off.** (done 2026-09-25, v1.2.15; element type, id and version are columns (DB v7, old ids backfilled in SQL); chips for osm.org, the web editor, Vespucci via its JOSM-remote-control URL and StreetComplete via `geo:`, both verified against the editors' manifests and source but not yet on a device, plus a `geo:` chooser; hand-off marks visited, "Dismiss for now" mutes; notes whose thread links an element get the same chips) From fixme details: open the element in an external editor. Start with an osm.org element URL and a `geo:` intent; then editor-specific intents (StreetComplete, Vespucci) once verified on a device. Keep the OSM element type and id as structured fields; today they only live inside the POI id string.
11. [x] **Evidence capture.** (done 2026-09-25, v1.2.16; an `evidence` table (DB v8, no foreign key so rows outlive the POI's expiry, with a snapshot of the item) fed by "Add photo" (camera into app-private files via FileProvider), "Pick photo" and "Add text" chips on the details sheet, each snapping the walker's fix; pinned rows show in the sheet with thumbnails, and a "To review" screen from a Home card lists them newest first with open / reviewed / share / delete; camera and viewers untested on a device) Photo or text pinned to a note or fixme, stored locally, listed in a "to review" view. No upload; this is for the desk session later.
12. [x] **Fixme list quality.** (done 2026-09-25, v1.2.17; fixme text and main tags per row, relation and age filters, swipe to dismiss, fixme labels, DB v9) Show the `fixme` text and the element's main tags in the list; filter out fixmes on relations or those older than a chosen age; dismiss-for-now. Reuse the item 8 label machinery for fixme text ("continue", "resurvey", "check name").
13. [x] **Settings for the loop.** (done 2026-09-25, v1.2.18; a "Pings and fetching" card in Settings with fetch radius 1/2/3/5 km, ping radius 25/50/100/200 m, class checkboxes, mute-notes and mute-fixmes switches, quiet hours with Material time pickers, the fixme age, and a "Muted items (N)" row to a list with Unmute and Unmute all; every write bumps a prefs tick the ping flow and the Notes tab follow, the service reads radius and quiet hours per fix; untested on a device) Fetch radius, ping radius, which classes ping, mute notes, mute fixmes, quiet hours. Also a way to see and unmute individual items muted from a ping (the `isMuted` flag from item 1 has no UI yet).
14. [x] **Battery pass.** (done 2026-09-25, v1.2.19; adaptive cadence and batching done; the two-hour walk measurement is still owed on a device) Location update interval adapts to speed; fetches batch behind the location cadence; measure a two-hour walk.
15. [x] **Rename.** (done 2026-09-25, v1.2.20; labels and docs only, package id and data files unchanged) App name, package label and docs from "Survey Me" to FixMeAwesome (package id stays `com.surveyme` unless a release is far off enough to change it).
16. [ ] **Release prep.** Adaptive icon, privacy policy (location, OSM account), ProGuard rules, signed build, listing text and screenshots.
17. [ ] **Tests around the loop.** Unit tests for the notes API client, the refetch trigger, proximity logic, and the classifier against `analysis/toronto_open_notes.json`. One instrumented test for the deep-link.

## Note classes

Decided 2026-09-23 from all 305 open notes inside the City of Toronto (corpus and prototype classifier in `analysis/`). Goal: help three kinds of mapper find the notes that are theirs: casual **telemappers** (imagery and web sources, iD), **surveyors** (on foot), and advanced **JOSM editors** (relations, routing, imports, restructuring). Three axes; a note can carry several labels on each.

**Audience** (derived from the other two axes):

- `surveyor`: someone must stand there. Explicit survey requests, questions ("is this still…?"), app reports whose free text is itself a question, and the address, cycling and walking, amenity-detail and accessibility topics.
- `telemapper`: someone already stood there or a source says what changed; the job is an edit. App reports where the reporter states what they saw ("playground torn down", "closed over a year ago", "no bus stop"), geometry and alignment notes, notes with a city or news source link.
- `josm`: relations, routing and lane problems, transit, data quality, import follow-ups.

**Topic**: `address`, `business`, `construction`, `transit`, `roads`, `cycling_walking`, `parks`, `accessibility`, `amenity_detail`, `geometry`, `data_quality`, `import_followup`.

**State**:

- `survey_requested`: text asks for a visit or a check.
- `scheduled`: carries a strict survey-after tag with a date. Hidden from pings and the default list until the date, then treated as `survey_requested`.
- `future_dated` (hint only): a parsed date after today, or "coming soon", "planned", "to be demolished". Shown in triage as "looks scheduled, tag it?". Not trusted for hiding, because loose parsing hits snapshot dates and past dates.
- `app_observed_needs_edit` / `app_needs_ground_check`: StreetComplete, Organic Maps, CoMaps and EveryDoor reports, split by whether the reporter's free text states an observation or asks a question.
- `has_element_link`, `has_photo`, `has_source_link`: structure that makes the note actionable at a desk.
- `duplicate_cluster`: same author, same day, near-identical text, within about 150 m. One visit or one editing session resolves the set.
- `discussed`, `anonymous`, `stale` (older than two years with no activity for one year), `low_signal` (three words or fewer, no topic, not an app report), `mine`, `note_to_self`.

**Strict tags** are public hashtags written into note comments, so other mappers see them; keep them readable. Already in use in Toronto: `#surveyme` (5 notes) and `#resurvey` (1). Vocabulary (decided 2026-09-23): `#surveyme` (needs a visit now), `#surveyafter 2027-03-01` (ISO date; hidden until then, then behaves as `#surveyme`; month-only `2027-03` means the first of the month), `#armchair` (telemapper), `#josm` (expert). `#resurvey` is read as `#surveyme`. Topic tags are optional and not needed for the loop.

**What the corpus showed** (2026-09-23, 305 open notes):

- Surveyor-class notes are about 59%, telemapper about 33%, JOSM about 30%; 14% fit no audience rule yet and 12% no topic.
- App-generated notes are 22% of the corpus. Of those, 61 state an observation and only 5 ask a question. Organic Maps and CoMaps "place does not exist" notes come with POI name, type and snapshot date and are good armchair deletions, not noise.
- Address notes are 16%, and about 45 of them are one author's May 2026 import follow-ups ("Please SURVEY addresses and entrances").
- Loose date detection found 36 "planned change" notes but only 6 with a parseable future date; the rest are "coming soon" and "future" with no date. The strict tag is the right call.
- 21 notes sit in duplicate clusters (seven playground notes from one StreetComplete session, four identical mini-pitch notes, four "What has HSBC become").
- 33 notes are stale and 10 are low-signal ("Very good city", a postal code, a business name).

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
