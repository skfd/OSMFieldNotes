Android app "OSM Field Notes" (package id still `com.surveyme`). For OpenStreetMap contributors who like long walks and want those walks to fix the map.

Main interface is a map. You move, zoom and do other normal map interactions. It shows you on the map and keeps tracking you in the background until you explicitly stop it; the system should not unload it.

Instead of a list of points you bring, the app pulls the things OSM itself says need a visit: open OSM Notes and objects tagged `fixme` around your position. It keeps fetching as you move.

When you get close to one, it pings you. Tapping the ping opens the note or the fixme.

For a note, you act in the app: read the thread, comment, close, or reopen it with your OSM account. You can also drop a new note where you stand.

For a fixme, the app hands you off to an editor (StreetComplete, Vespucci, osm.org) that can change map data; this app does not edit map data itself.

If you cannot deal with it on the spot, you capture evidence (photo, text) pinned to the point and review it at home.

Track recording exists and stays, but it is not what the app is for.
