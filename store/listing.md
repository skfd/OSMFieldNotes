# Store listing text

Source of truth for the Play Store (and any other store) listing. Keep the lengths: title
30 characters, short description 80, full description 4000.

## Title

OSM Field Notes

## Short description (80 characters max)

Pings you near open OSM notes and fixmes. Walk, then fix the map on the spot.

## Full description

OSM Field Notes is for OpenStreetMap contributors who like long walks and want those walks to
fix the map.

Instead of a list of points you bring along, the app pulls the things OpenStreetMap itself
says need a visit: open OSM Notes and objects tagged "fixme" around you. It keeps fetching as
you move, and pings you when you get close to one.

THE LOOP

1. Start tracking. It runs in the background, with a visible notification, until you stop it.
2. Notes and fixmes around you are fetched from openstreetmap.org and the Overpass API, and
   refetched as you walk on.
3. When you are close, you get a ping. Tap it and the item opens.
4. Notes are handled in the app: read the thread, comment, close, reopen, or drop a new note
   where you stand. Actions taken offline are queued and sent later.
5. Fixmes are handed to a real editor: StreetComplete or Vespucci if installed, or
   openstreetmap.org. OSM Field Notes does not edit map data itself.
6. Cannot deal with it now? Pin a photo or a text to the item and review it at your desk later.

WHAT ELSE

- Notes are sorted by who they are for: surveyor (someone has to stand there), armchair
  (imagery is enough) or JOSM (relations, routing, transit). Only surveyor notes ping by
  default; you choose.
- Morning triage: the notes that appeared since you last looked, newest first, one tap to tag
  them for the walk or for the desk.
- Fixme rows show the fixme text and the object's main tags; filter by age, dismiss for now.
- Settings for fetch radius, ping radius, quiet hours, and what pings.
- Track recording and GPX waypoint import are there too.
- Location cadence adapts to how fast you move to save battery.

YOUR ACCOUNT

An OpenStreetMap account is optional. Log in (OAuth 2.0, in your browser, the app never sees
your password) to comment on and create notes under your name. Without it you can still track,
get pinged, hand off fixmes, keep evidence, and post anonymous notes.

PRIVACY

Everything stays on your phone. The app sends only the map area it needs to
openstreetmap.org and overpass-api.de (plus one Nominatim lookup if you pick a whole city), and what you choose to post. No analytics, no ads, no
crash reporting service. Full policy:
https://github.com/skfd/OSMFieldNotes/blob/main/PRIVACY.md

Free and open source. Map data (c) OpenStreetMap contributors.

## Categories

- Play category: Maps & Navigation (alternative: Tools)
- Tags: OpenStreetMap, OSM, notes, fixme, mapping, survey, field survey, walking

## Content rating notes (IARC questionnaire)

- No violence, sexual content, language, controlled substances or gambling.
- User interaction: yes. Users can post text that other OSM users see (notes and comments on
  openstreetmap.org). No in-app messaging between users; nothing user-generated is shown that
  is not also public on osm.org.
- Shares location: the app sends a map area to OSM servers; a note the user posts carries a
  position and is public. Location is not shared with other users through the app.
- No purchases, no ads.
- Expected rating: Everyone / PEGI 3 with the "Users Interact" and "Shares Location"
  descriptors.

## Data safety form (Play Console)

- Location (approximate and precise): collected, used for app functionality, required for
  the core feature, stored on the device only, not sent to the developer, not shared with
  third parties by the developer.
- Personal info: OSM display name and user id, stored on the device only after the user logs
  in; not sent to the developer.
- Photos: user-added evidence stays on the device.
- All network traffic is HTTPS to openstreetmap.org (including nominatim.openstreetmap.org) and overpass-api.de; the developer runs
  no server and holds no copy of any user data.
- Deletion: uninstall or clear storage; nothing to request from the developer.

## Permissions to explain in the listing / Play declaration

- Background location: the app pings the walker near notes and fixmes while the screen is off
  and the app is not open; this is the core function. A prominent in-app disclosure precedes
  the request (Settings > Permissions).
- Foreground service (location): the tracking notification.
- Notifications: the pings.
