# To test on a device

Roadmap items 2 through 17 were built on 2026-09-25 and 2026-09-26 without a device attached. Every build compiled and the unit tests pass, but none of it has run on hardware. Work through this file top to bottom; the first section gates everything else.

## Before anything else

- Install v1.2.22 **over the existing v1.2.6 install** (not a fresh install). That runs six Room migrations (3 to 9) on real data in one go. Open the Notes tab, Tracks tab and Settings: no crash, old notes and tracks still listed.
- Register the OSM OAuth client (steps in FIRST_RUN.md: https://www.openstreetmap.org/oauth2/applications/new, redirect URI exactly `osmfieldnotes://oauth/callback`, "Confidential application" unchecked, permissions `write_notes` and `read_prefs`), put `OSM_OAUTH_CLIENT_ID=...` in local.properties, rebuild. Items 5 to 9 are untestable until then.

## Item 2: fetch follows the walker (v1.2.7)

- Start tracking, walk more than 1 km in the GPS simulator; the log should show `osm_notes: fetching (moved ...)`, `osm_fixme: fetching ...`, then `Fetch (WALKER) ...: notes: N found, M expired`; the Notes tab reflects the new area.
- Open the Map tab mid-walk: log says `Map load fetch skipped: tracking owns the fetch centre` and the POI count does not drop.
- Settings "Find notes" twice within 60 s: both fetch (manual jumps the min interval); toast shows per-source counts and says "around your position" while tracking.
- Airplane mode, tap Find: toast shows the failure with "retry in 120s"; tap again: "skipped (backing off ...)"; back online, wait it out, the next tap fetches.
- Mute a note, walk 1.5 km away and back: it returns unmuted (documented; item 13 territory).

## Item 3: first-class note model (v1.2.8)

- Settings fetch: rows show real created dates and "N replies"; a recently closed note shows a "NOTE · CLOSED" chip and close date.
- Mute a note from a ping, refetch: still muted.
- GPS-sim past a closed note: no ping; past an open one: pings as before.
- Move more than 1 km and refetch: logcat "Expired"/"Dropped" lines; the Settings toast's expired count includes dropped notes.

## Item 4: note details rework (v1.2.9)

- Tap a note in the Notes tab: full-height sheet, OPEN pill, created date, thread rows "author action · date" oldest first; scroll a long thread, then drag down from the top to dismiss.
- Open a closed note (closed within 7 days): muted CLOSED pill, "closed ..." date, a "user closed" row even when the close had no text.
- Tap a fixme: FIXME pill, fixme text first, then tag rows; "Open on osm.org" opens the node/way page.
- Tap the "Note nearby" ping with the app cold and with it open on the Map tab: the sheet opens on the Notes tab with "N m away".
- "Show on map" switches to the Map tab; a comment older than a week shows an absolute date; an anonymous comment shows "Anonymous".

## Item 5: OSM login (v1.2.10)

- No client id configured: Settings > Log in toasts "No OSM client id configured", nothing opens.
- With a client id: the Custom Tab shows the osm.org authorize page with both scopes; approve, the app returns, toast "Logged in as <name>", the card flips to name + Log out.
- Deny on osm.org: "Login cancelled on osm.org", still logged out.
- Kill the app while the tab is open, then approve: a cold start still completes the login.
- Open a note's details while logged in: the Comment/Close row is visible; fixmes show no row.
- Log out: the card flips back, the details row is hidden.
- If debug (.debug suffix) and release are both installed, an app chooser appears on the redirect.

## Item 6: comment, close, reopen (v1.2.11)

- Log in, open an open note, Comment > Send: a pending row appears, then becomes the server's entry with a real date.
- Close with a comment: the pill flips to CLOSED at once, Comment/Close vanish, Reopen shows; the server entry follows.
- Airplane mode, Close: the header says "1 action pending"; restore the network, the queue drains without reopening the sheet.
- Close a note already closed on osm.org: toast with osm.org's 409 text, status returns to what the server says.
- Queue two actions offline, kill the app, relaunch: drains on start, in order.

## Item 7: create a note (v1.2.12)

- Airplane mode, Map FAB, post: "NOTE · PENDING" in the Notes tab and an upload marker on the map; network back, the row becomes "OSM Note (<real id>)" with an osm.org link.
- Logged out, Post: "Post anonymously?" appears once; the created note's opening comment shows Anonymous.
- Long-press 3 km away: the confirmation shows coordinates; post; a Settings fetch does not remove the placeholder.
- Upgrade from v1.2.11 with a queued comment: the app opens, no migration crash, the comment still drains before a later create.
- Tracking on, stand on your own fresh note: no "Note nearby" ping.
- Home "Create note here" with no fix yet: "Location not available yet" toast.

## Item 8: note classification (v1.2.13)

- Upgrade over v1.2.12 data, open Notes: old rows show label chips; surveyor notes carry "NOTE · PINGS"; the log has "Classifier v1: relabelled N".
- Pick the Telemapper chip, kill and relaunch: chip and list agree; fixmes disappear under Telemapper/JOSM, return under All/Surveyor.
- Map: note pins green/blue/purple/grey by audience; a pending create still shows the upload icon.
- Simulator walk past a telemapper-only note and a surveyor note: only the surveyor one pings; a fixme still pings.
- Comment `#surveyafter 2027-03` on a nearby note and refetch: "scheduled" chip, sorted lower, no ping; `#armchair` turns it blue and silent.
- The details sheet shows the full label set for a note; fixme details show none.

## Item 9: morning triage (v1.2.14)

- Fresh: the Home card says "N open notes, never triaged"; Triage lists all open notes newest first, switch disabled; a row tap opens the details sheet.
- Logged out: tap a chip, a dialog offers Open Settings, which lands on Settings; nothing queued.
- Logged in, offline: tap the highlighted chip, toast, the row shows "Pending: #x"; go online, "Tagged #x", the Notes tab audience filter reflects it.
- `#surveyafter...` refuses past dates; the comment text is exactly `#surveyafter YYYY-MM-DD`; the note becomes scheduled and stops pinging.
- Post one tag, press Up (not Done): the Home count drops; re-enter Triage: switch enabled, "Show all" brings the rest back.
- Swipe/Skip a row: hidden; leave and return: back (no stamp).

## Item 10: fixme hand-off (v1.2.15)

- Upgrade from a v6 install, open an old fixme: the chip row appears without a refetch (backfill); no crash on DB open.
- Fresh fetch: fixme details show "Fetched ... · vN"; osm.org / Edit online open the element / iD.
- With Vespucci installed: the chip appears, opens with the element selected and the area loaded; without it, no chip.
- "Dismiss for now": the pill and list row turn "FIXME · DISMISSED" dimmed, walking past no longer pings, Undo restores; state survives closing and reopening the sheet and a background refetch.
- A note whose thread links an osm.org node/way URL shows the chip row (no Dismiss chip); one without a link shows none.
- "Open map here" offers a chooser (StreetComplete, Maps, ...).

## Item 11: evidence capture (v1.2.16)

- Open a note, tap "Add photo", shoot: "Photo pinned" toast, an "Evidence (1)" row with a correctly rotated thumbnail; tap it, it opens in a viewer.
- Same on a fixme and on a just-created pending note; after the create posts, the evidence should follow to the real note id.
- "Add text": a row appears; the Home card count goes up; "To review" lists it newest first with distance.
- Expire or delete the item (fetch far away): "Open item" shows the snapshot dialog, no crash.
- Reviewed / Not reviewed and the "Show reviewed" switch; Delete removes the row and the file under `files/evidence/`.
- Share a photo row to another app; the JPEG arrives with the caption.

## Item 12: fixme list quality (v1.2.17)

- Upgrade over v1.2.16 data: no crash; old fixme rows show "Fetched ..." until the next Overpass fetch flips to "Edited ... · vN"; label chips appear after first launch.
- A fixme row reads: fixme text bold, "Name · amenity=cafe · node" below, up to 4 label chips (continue / resurvey / check name / check position / check tags).
- Overflow menu > Fixme age > "Up to 1 year" hides old fixmes (and shows "Nothing matches the filters" if all go), survives restart; "Any age" brings them back.
- Swipe a fixme: it dims, drops to the bottom, Snackbar with Undo above the bottom nav; swipe a dismissed one restores it; note rows do not swipe.
- Ping gate while tracking: a dismissed or too-old fixme stays silent, a recent one pings.
- Details sheet of a fixme: "Edited <date> · vN · node · visited" and all label chips; Dismiss chip and swipe agree.

## Item 13: settings for the loop (v1.2.18)

- Quiet hours on with defaults: ping at 21:59, silence at 23:00 and 03:00, ping again after 07:00; the fetch toast still reports fetches inside the window.
- Change the fetch radius while tracking: the next fix logs "radius changed" and refetches; the toast's expired count matches; 5 km in the core shows whether the notes count looks capped at 500.
- Tick Telemapper: a blue-pinned note gains "NOTE · PINGS" in the Notes tab at once; untick Surveyor: green ones lose it. Mute notes / Mute fixmes: pings stop, lists and map unchanged.
- Ping radius 200 m: a note about 150 m away pings; at 25 m it does not.
- Mute from a ping notification, the Settings row count increments live, Muted items shows it with kind and first line, tap opens the details sheet, Unmute (Undo snackbar), re-pings only after a tracking restart; Unmute all with confirmation.
- Fixme age from Settings and from the Notes tab menu agree both ways; General "Notifications" off silences pings.

## Item 14: battery pass (v1.2.19)

- Start tracking; Debug > View logs shows `Cadence: start -> walking`; walk, then stand 3 min: `-> stationary`; set off: `-> walking` on the first fix (no spurious stationary line right after Start).
- Background the app with the Notes tab open, stand 3 min: the log shows `NotesFragment: own location updates stopped` and few fixes; Settings > Battery stats counters agree.
- Walk from a standstill toward a note about 60 m away with ping radius 50 m: does it ping, and how late?
- Move more than 1 km: exactly one `Fetch (WALKER)` line per move, not one per fix.
- Two-hour measurement: charge to a known %, Start, screen off, walk 2 h, Stop; read Settings > Battery stats and grep `Battery:` / `Cadence:` in the log; compare the % drop and fix count with a v1.2.18 walk of similar length.

## Item 15: rename (v1.2.20)

- Install over the existing build and check notes, tracks, OSM login and settings survive.
- Launcher icon and recents read "OSM Field Notes".
- Start tracking: the notification title reads "OSM Field Notes - Tracking".
- Revoke location and re-grant: the dialogs say "OSM Field Notes needs...".

## Item 16: release prep (v1.2.21)

- Install the debug build: the launcher icon shows the white pin with the orange "!" on blue; on Android 13+ enable themed icons and check the monochrome pin.
- Settings > About > Privacy policy opens the GitHub PRIVACY.md in the browser.
- Install app-release-unsigned.apk (or a signed one): log in, fetch notes and fixmes, open a note thread, post a comment, add a photo; R8 bugs only surface here.
- With RELEASE_* set in local.properties, `assembleRelease` yields app-release.apk; `apksigner verify --print-certs` shows your cert.
- A fresh clone without RELEASE_* still builds unsigned.

## Item 17: tests around the loop (v1.2.22)

- `.\run-tests.ps1` should print "Unit tests: 131 in 15 classes, 0 failed".
- Device attached: uninstall `com.surveyme.debug` (or clear data) so the Home crash-log dialog cannot cover the sheet, then `.\gradlew.bat connectedDebugAndroidTest`; the report lands in `app\build\reports\androidTests\connected\`. On API 33+ the rule grants POST_NOTIFICATIONS itself.
- Short walk sanity: refetch after about 1 km or 15 min, one ping per item with the 5 min cooldown (nothing behaviour-visible should have changed).

## Still owed by the maintainer

- License decision (MIT vs GPL-3.0) before pushing; RELEASE.md lists it as a publishing blocker.
- Real keystore outside the repo (FIRST_RUN.md) and the four RELEASE_* keys.
- Six screenshots per store/screenshots/README.md and a feature graphic.
- The two-hour battery walk (item 14) and the connected deep-link test (item 17).
