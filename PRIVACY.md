# OSM Field Notes privacy policy

Last updated 2026-09-26. Applies to the Android app OSM Field Notes (package id `com.surveyme`).

The short version: the app keeps everything on your phone, talks to openstreetmap.org and the
Overpass API only to do what you asked it to do, has no account of its own, no analytics, no
advertising, and no crash reporting service. The maintainer never sees your data.

## What the app is for

OSM Field Notes tracks your position while you walk, fetches the open OpenStreetMap notes and
`fixme`-tagged objects around you, pings you when you get close to one, and lets you act on it:
read and comment on notes with your OSM account, hand a fixme to an external editor, or pin a
photo or a text to it for later. Every item below exists to serve that loop.

## Location

**Why.** The app needs your position to know which notes and fixmes are near you and to ping
you when you get close. Background location is requested because the whole point is to keep
watching while the screen is off and you walk; tracking runs as a foreground service with a
visible notification until you stop it yourself.

**Where it goes.** Position fixes are stored only in the app's private Room database on the
device (`survey_me_database`): the track you are recording, the last fix used for distance and
for ping decisions, and the fix attached to each piece of evidence you capture. The last
position is also remembered in the app's preferences so the map can open where you were; see
"Other data on the device" for what Android's own backup does with preferences. No fix is
uploaded anywhere by the app.

**What leaves the device because of your position.**
- To fetch notes and fixmes the app sends a bounding box (a rectangle of roughly the fetch
  radius you chose, 1 to 5 km, around your position) to `api.openstreetmap.org` and to
  `overpass-api.de`. The server sees that rectangle and your IP address, not your exact fix
  and not your track.
- Map tiles come from the standard OpenStreetMap tile server through the osmdroid library.
  The tile server sees which map area you look at, at which zoom, and your IP address. Tiles
  are cached in the app's private cache directory.
- When you create a note, its position (your fix or the point you long-pressed) and your text
  are posted to openstreetmap.org and become public, permanently, under the OSM licence, as
  every note does. The app tells you before posting anonymously.

The app does not use any other location service, geocoder, or third-party map provider.

## Your OpenStreetMap account

Logging in is optional. Without it you can still track, fetch, get pinged, hand off fixmes,
keep evidence, and (after a confirmation) post anonymous notes.

**What is stored.** After you log in through the OpenStreetMap OAuth 2.0 page in a browser
tab, the app keeps the access token, your display name and your user id in
`EncryptedSharedPreferences` (file `osm_auth.xml`), encrypted with a key held in the Android
Keystore of this device. That file is excluded from Android backups and device transfers. The
app never sees or stores your OSM password.

**Scopes.** The app asks for `write_notes` (comment on, close, reopen and create notes as you)
and `read_prefs` (read your display name). It cannot edit map data and never asks for that
scope.

**What is sent as you.** Only what you tap: a comment, a close, a reopen, a new note, and the
triage tags (`#surveyme`, `#surveyafter`, `#armchair`, `#josm`) posted as comments. Each of
these goes to `api.openstreetmap.org` with your token and becomes public on osm.org under your
name. Actions taken while offline wait in a queue on the device and are sent when a connection
returns; you can see them as "pending" on the note until then.

**Log out.** Settings > OSM account > Log out deletes the token from the device and asks
openstreetmap.org to revoke it (best effort). You can also revoke it yourself at
https://www.openstreetmap.org/oauth2/authorized_applications at any time.

## What the app sends to servers, and how it identifies itself

| Server | When | What |
| --- | --- | --- |
| `api.openstreetmap.org` | fetching notes; posting note actions | bounding box; note id, your text, your token (only when logged in) |
| `www.openstreetmap.org` | login, logout | the OAuth 2.0 authorization flow; token revoke |
| `overpass-api.de` | fetching fixmes | an Overpass QL query with a bounding box |
| `tile.openstreetmap.org` | showing the map | tile coordinates |

Requests to the OSM API and Overpass carry the User-Agent `OSMFieldNotes/<version>`; map tile
requests carry the package id, as osmdroid's usage policy requires. Nothing else is sent: no
device id, no advertising id, no usage statistics.

The maintainer runs none of these servers. Their handling of IP addresses and request logs is
described in the OpenStreetMap Foundation privacy policy
(https://osmfoundation.org/wiki/Privacy_Policy) and the Overpass API operator's terms.

## Photos and evidence

"Add photo", "Pick photo" and "Add text" on a note or fixme store the photo or text in the
app's private storage (`files/evidence/`) together with the item's id, a snapshot of its
title and coordinates, and your fix at the time. The app declares no camera permission and no
storage permission on any Android version; the camera and the photo picker are system
components that hand the app one file. Evidence is never uploaded by the app; "Share" hands the file to an app you pick.
Deleting an evidence row deletes its file.

## Other data on the device

- Notes and fixmes fetched around you, your labels, mutes, dismissals and settings, in the
  private database and in `SharedPreferences`.
- GPX files you import, parsed into the same database.
- `crash_log.txt` in private storage if the app crashes; debug builds also write
  `app_log.txt`, which can contain position fixes. Neither file is sent anywhere unless you
  share it yourself.

Preferences (not the token, not the database, not the evidence files) are included in
Android's own backup if you have device backup switched on. They hold your settings and the
last position the app saw, and go to your Google account under Google's terms, not to the
maintainer. Switch off app backup in Android settings if you do not want that.

## Third parties

The app contains no analytics, advertising or crash-reporting SDK. The only Google component is
Google Play Services Location, which supplies position fixes on devices that have Play
Services; its processing is covered by Google's privacy policy. The mapping and networking
libraries (osmdroid, OkHttp, Room, Gson, Tink) run entirely on the device.

## Deleting your data

- **Everything:** uninstall the app, or Android Settings > Apps > OSM Field Notes > Clear storage.
  Both remove the database, evidence files, preferences and the token.
- **Just the account:** Settings > OSM account > Log out.
- **Just evidence:** the To review screen deletes rows and their files.
- **Just tracks or POIs:** the Tracks tab and Settings > POIs > Clear.
- Notes and comments you posted live on openstreetmap.org under its rules; the app cannot
  delete those.

## Children

The app is not directed at children and collects nothing from anyone.

## Changes and contact

This file is versioned with the source code at
https://github.com/skfd/OSMFieldNotes/blob/main/PRIVACY.md; changes show in its history. Questions
go to the issue tracker of that repository.
