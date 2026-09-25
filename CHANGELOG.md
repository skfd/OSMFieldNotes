# Survey Me - Changelog

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