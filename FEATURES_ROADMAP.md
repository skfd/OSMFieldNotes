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
1. [ ] **Ping opens the item.** The proximity notification deep-links to the note or fixme details, not to MainActivity. Include a mute-this-one action. Item 8 later decides which notes are allowed to ping at all.
2. [ ] **Fetch follows the walker.** Refetch notes and fixmes when the user has moved more than half the fetched radius from the last fetch centre, from the tracking service, not only from the map or Settings. Expire items outside the radius. Respect Overpass and osm.org rate limits.
3. [ ] **First-class note model.** Store OSM note id, status, creation date and the comment thread as structured data instead of a flattened text field. Keep closed notes so a note you just closed can still be shown. Reserve a `labels` set and a nullable `surveyAfter` date on the note now, so item 8 needs no migration.
4. [ ] **Note details rework.** Thread rendered as comments with author and date, status shown, link to osm.org.
5. [ ] **OSM login (OAuth 2.0 with PKCE).** Register an OAuth 2 client at osm.org, scope `write_notes`. Store the token in EncryptedSharedPreferences. Settings shows who is logged in and a logout. OSM only supports OAuth 2.0; the redirect must be HTTPS or a native scheme, confirm the native-scheme option during client registration.
6. [ ] **Comment, close, reopen a note** from the details view via `POST /api/0.6/notes/{id}/comment|close|reopen`. Optimistic update, retry queue when offline.
7. [ ] **Create a note** where you stand, or at a long-press on the map. Creation is anonymous-capable in the API but should use the logged-in user when available.
8. [ ] **Note classification.** Built-in rules only, no user-defined rules yet. Labels are computed locally from note text and metadata and stored on the note. See "Note classes" below for the classes, the evidence, and the strict tag vocabulary. Strict tags written by mappers win over heuristics. Source app is shown as a label but is never a filter. Classes drive: which notes ping (default: surveyor classes only, never future-dated, never stale), list order, marker colour, and a per-audience filter in the list. Prototype and corpus in `analysis/`.
9. [ ] **Morning triage.** A "new since last triage" view of notes in the home area, sorted newest first, with one-tap buttons that post the strict tags as comments (`#surveyme`, survey-after with a date picker, and the audience tags). Shows the heuristic guess so the tap is a confirmation. Depends on 6 and 8.
10. [ ] **Fixme hand-off.** From fixme details: open the element in an external editor. Start with an osm.org element URL and a `geo:` intent; then editor-specific intents (StreetComplete, Vespucci) once verified on a device. Keep the OSM element type and id as structured fields; today they only live inside the POI id string.
11. [ ] **Evidence capture.** Photo or text pinned to a note or fixme, stored locally, listed in a "to review" view. No upload; this is for the desk session later.
12. [ ] **Fixme list quality.** Show the `fixme` text and the element's main tags in the list; filter out fixmes on relations or those older than a chosen age; dismiss-for-now. Reuse the item 8 label machinery for fixme text ("continue", "resurvey", "check name").
13. [ ] **Settings for the loop.** Fetch radius, ping radius, which classes ping, mute notes, mute fixmes, quiet hours.
14. [ ] **Battery pass.** Location update interval adapts to speed; fetches batch behind the location cadence; measure a two-hour walk.
15. [ ] **Rename.** App name, package label and docs from "Survey Me" to FixMeAwesome (package id stays `com.surveyme` unless a release is far off enough to change it).
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
