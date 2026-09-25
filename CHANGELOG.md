# Survey Me - Changelog

## Version 1.2.13 (2026-09-25)

**Roadmap item 8**: "build it as far as you can" (note classification)
`NoteClassifier` (pure Kotlin, `core/`) ports every rule of `analysis/classify_notes.py`: topics on the boilerplate-stripped opening comment, survey requests, app reports (StreetComplete, Organic Maps, CoMaps, EveryDoor) split into "observed, needs edit" and "needs ground check", element/photo/source links, anonymous, discussed, stale, low signal, mine, note to self, loose future dates as a `future_dated` hint only, and duplicate clusters (same author, day, first four words, within the prototype's 600 m) computed in the repository over the fetched batch plus what is stored; strict tags `#surveyme` / `#resurvey`, `#surveyafter YYYY-MM[-DD]` (`scheduled` until the date, with the date persisted in `surveyAfter`), `#armchair` and `#josm` replace the heuristic audience. Labels are recomputed on every store (fetch, post answer, create placeholder), once for all stored rows on the first launch after this build (a `classifier_version` pref), and after a login or logout for `mine`. `NotePingPolicy` decides what pings: fixmes as before, notes only when open, surveyor-class, not stale and past their `#surveyafter` date (checked against the clock on each emission), so telemapper- and JOSM-only notes stay silent. The Notes tab gets an All / Surveyor / Telemapper / JOSM chip row persisted in prefs (fixmes show under All and Surveyor), lists notes that would ping now first and then by distance, marks them "NOTE · PINGS", and shows up to four label chips per row with all of them in the details header; map note markers are pins tinted by audience (green surveyor, blue telemapper, purple JOSM, grey none). Unit tests cover the rules, the ping policy and the 305-note corpus (`app/src/test/resources`), where the port reproduces the prototype's counts exactly (surveyor 180, telemapper 102, JOSM 93 vs 94).

## Version 1.2.12 (2026-09-25)

**Roadmap item 7**: "build it as far as you can" (create a note)
A new note can be composed where you stand (a FAB on the Map tab and a "Create note here" button on Home, from the tracking service's fix or the platform's last known one) or at a long-press on the map, which first confirms the coordinates; the dialog shows "Posting as <name>" or, logged out, "Posting anonymously" and then asks "Post anonymously?" once with a way to the login, and Post is disabled while the text is blank. The note goes into item 6's `note_actions` queue as a `create` row (DB v6: nullable `lat`/`lon` added with ALTER TABLE, schema 6.json exported) and is shown at once as a muted placeholder with a negative id ("NOTE · PENDING" in the list, an upload marker on the map, no actions or osm.org link in details, protected from refetch expiry); `POST /api/0.6/notes.json?lat=&lon=&text=` is sent with the Bearer token when there is one, anonymously only if the note was composed while logged out, and on success the server's note replaces the placeholder under its real id, muted and marked visited so it cannot ping its author (the DB `isVisited` flag is not what the detector gates on; `isMuted` is), while a hard refusal drops the placeholder with the reason and first line of the text in a toast. Nothing has been posted to osm.org yet: the whole path is untested until the maintainer registers an OAuth client or tries an anonymous create.

## Version 1.2.11 (2026-09-25)

**Roadmap item 6**: "build it as far as you can" (comment, close, reopen)
The note details sheet now posts comments, closes and reopens with the logged-in user's token: Comment opens a text dialog with Send, Close and Reopen take an optional comment, and an open note shows Comment + Close while a closed one shows Reopen. Every action is written first to a `note_actions` queue table (DB v5, create-only migration) and applied optimistically (a "pending" thread row, "N actions pending" in the header, status flipped locally for close/reopen); `NoteActionQueue` drains it oldest-first on app start, when a network with internet appears, after login and after each post, replacing the stored note with the server's answer on success, backing off (30 s to 10 min, Retry-After honoured) on network trouble, 429 and 5xx, and on any other 4xx dropping the row, refreshing the note from osm.org and toasting the server's text; a 401 also clears the login. `OsmNotesService` now uses the OAuth base URL for fetches too, so a dev-server token never reaches production. Nothing has been posted to osm.org yet: the whole path is untested until the maintainer registers an OAuth client.

## Version 1.2.10 (2026-09-25)

**Roadmap item 5**: "build it as far as you can" (OSM login)
`OsmAuthManager` runs OAuth 2.0 Authorization Code with PKCE (S256, public client, scopes `write_notes read_prefs`) against `/oauth2/authorize` and `/oauth2/token`, opens the authorize page in a Chrome Custom Tab and returns through a `fixmeawesome://oauth/callback` intent-filter on MainActivity; the token, display name and uid (from `user/details.json`) live in EncryptedSharedPreferences, excluded from backup. Settings has an account card ("Logged in as ..." with Log out, or "Log in to OpenStreetMap"), and the note details action row now follows real login state (posting itself is item 6). The client id is read from `local.properties` (`OSM_OAUTH_CLIENT_ID`, see `local.properties.example`); no client is registered yet, so the whole flow is untested against osm.org until the maintainer registers one, and without an id the login button says so instead of launching.

## Version 1.2.9 (2026-09-25)

**Roadmap item 4**: "build it as far as you can" (note details rework)
The details sheet now takes the full screen and renders a note's thread as a RecyclerView of comments, each with author (or "Anonymous"), action (opened / commented / closed / reopened), a relative date within the last week or a short absolute one, and the text; bare close and reopen entries get a row, empty plain comments do not. The header shows an Open / Closed / Hidden status pill, created and closed dates, reply count and the distance from the walker (from the list, or from the last known fix when opened from a ping or marker). "Open on osm.org" links to the note page, or to the element page for a fixme whose `overpass_{type}_{id}` id is derivable; fixmes show their fixme text and raw tags as key/value rows, and "Show on map" still round-trips to the map. A comment / close / reopen action row is laid out but hidden until OSM login (item 5) and posting (item 6) exist.

## Version 1.2.8 (2026-09-25)

**Roadmap item 3**: "build it as far as you can" (first-class note model)
Notes now have their own `osm_notes` table (DB v4, real migration): note id, status, created and closed dates, the comment thread as structured entries (date, author or null for anonymous, uid, action, text), plus `labels` and `surveyAfter` reserved for classification. The POI row stays the thing on the map and keeps its ping flags. The parser reads the real notes.json date format (the old ISO pattern silently fell back to "now"), keeps closed notes, and a refetch drops in-radius notes the API no longer returns unless they were closed within 7 days; closed notes are excluded from proximity pings. The Notes tab and details sheet show status, close date and reply count from the structured data; the full thread rendering is item 4.

## Version 1.2.7 (2026-09-25)

**Roadmap item 2**: "build it as far as you can" (fetch follows the walker)
All note and fixme fetching now goes through one `NearbyFetcher`: the tracking service refetches around the walker once they are more than 1 km (half the 2 km radius) from the last fetch centre or the last fetch is older than 15 min, and rows of that source outside the radius are expired after each successful fetch, keeping muted, visited and last-ping flags on the survivors. osm.org and Overpass each get their own gate with a 60 s minimum interval and exponential backoff (2 min doubling to 30 min, honouring Retry-After) on HTTP or network failure; two fetches never overlap. Map load and the Settings button use the same path, the map now fetches fixmes too, and the Settings toast reports per-source counts or the skip reason. While tracking, the map tab does not fetch and the Settings button fetches around the walker, so a stale map position cannot expire the walker's set.

## Version 1.2.6 (2026-09-25)

**Roadmap item 1**: "go" (ping opens the item)
The proximity notification now deep-links to the note or fixme details in the Notes tab and carries a "Mute this one" action. Muted is a new `isMuted` column (DB v3); refetching notes keeps muted, visited and last-ping flags instead of wiping them. Notification title says "Note nearby" or "Fixme nearby" with the distance and first line of text.

## Version 1.2.5 (2026-09-24)

**Roadmap item 0**: "continue yes" (fixmes actually reach the list)
Fixme POIs are now saved with source `osm_fixme` so the Notes tab query finds them. Rows left by earlier builds under source `overpass` are deleted on map load and before each Settings fetch.

## Version 1.2.4 (2026-03-15)

**Phase 8**: Database Setup (Room) - "implement"
Added TrackEntity, LocationPointEntity, TrackDao, database migration, and backup utility to lay the groundwork for track persistence.

## Version 1.2.3 (2025-09-18)

**Toronto Data**: "read file from data_samples folder"
Integrated 20 Toronto Bike Share stations as default POI data loaded from JSON on app launch.

## Version 1.2.2 (2025-09-18)

**Phase 13**: Proximity Detection - "notifications are not sent"
Fixed Android 13+ notification permissions and added runtime permission request.

**Phase 12**: POI Display on Map
Added POI markers to map with category icons and auto-insertion of sample data.

**Phase 10-11**: POI System Foundation
Created Room database for POIs with GPX import support and type converters.

**GPS Tools**: "create gps walker"
Built PowerShell GPS simulators with WASD controls for testing without physical movement.

**Build System**: Automation setup
Created build-install.ps1 script and fixed gradle-wrapper.jar issue.

**Bug Fixes**: App crash on fragment destroy
Fixed NullPointerException in MapFragment and added missing manifest permissions.