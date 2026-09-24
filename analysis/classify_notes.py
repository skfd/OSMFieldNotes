"""Prototype OSM note classifier. Reference for the Kotlin implementation (roadmap item 8).

Corpus: analysis/toronto_open_notes.json
  fetched 2026-09-23 from https://api.openstreetmap.org/api/0.6/notes.json
  bbox=-79.6393,43.5810,-79.1153,43.8555 (City of Toronto), closed=0, limit=10000
  305 open notes at fetch time.

Usage: python analysis/classify_notes.py [--show CLASS] [--unclassified]

Three axes, named after the three audiences the maintainer wants to serve:
  audience - telemapper (armchair, imagery/web), surveyor (on foot), josm (relations, imports, restructuring)
  topic    - subject bundles
  state    - flags derived from metadata and structure
"""
import json, re, sys, datetime, collections, math

TODAY = datetime.date(2026, 9, 23)

# Boilerplate emitted by apps; stripped before keyword and date matching.
BOILERPLATE = [
    r"osm snapshot date: \S+",
    r"via streetcomplete(_ee)? [\d.]+",
    r"#organicmaps \S+ \S+", r"#comaps \S+ \S+",
    r"a (comaps|organic maps)? ?user (of organic maps application )?(has )?reported that the poi was visible on the map \(see snapshot date below\), but was not found on the ground\.?",
    r"the place has gone or never existed\.?",
    r"attached photo\(s\):( \S+)*",
]

TOPICS = {
    "address": r"\baddress|house ?number|addressing|interpolation|\bunits?\b|\bentrances?\b|building number|laneway|lanehouse|lane housing",
    "business": r"coming soon|closing|to become|now a |is it closed|still (a |open|here|in business)|what has .* become|changed name|renamed|no longer|self storage|opening 20\d\d|is this still|not in business|does not exist|has gone|torn down|check if( this is)? still|closed (over|several)",
    "construction": r"construction|demolish|demolition|redevelop|new (building|townhouse|condo|highrise|semi)|being built|development|coming here|tall building|highrises|towers? built",
    "transit": r"\bttc\b|\bgo (station|bus|transit|\d)|bus (stop|loop|terminal|route)|subway|station|platform|metrolinx|ontario line|up express|presto|busway|streetcar|tram",
    "roads": r"turn restriction|\blanes?\b|\bhov\b|maxspeed|km ?per ?hour|km/h|traffic (signal|light|control)|red light camera|one[- ]way|no left|routing|motorway|\blinks?\b should|median|road (layout|is)|widening|highway exit|intersection|area signs",
    "cycling_walking": r"bike|bicycle|cycle|cycleway|sharrow|contraflow|footpath|footway|\btrail\b|\bpath\b|crossing|crosswalk|curb|tactile|walkway|pedestrian|stairs|ramp|\bford\b",
    "parks": r"\bparks?\b|playground|pitch|tennis|golf|cemetery|greenspace|trail system|waterfront|creek|putting green|tee box",
    "accessibility": r"wheelchair|elevator|\blift\b|tactile|accessib|stairs inside|ramp",
    "amenity_detail": r"opening hours|washroom|toilet|bench|shelter|recycling|collection box|pickup times|bottle refill|drinking water|little free library|parking lot|who is allowed to park|food ?court|plaque|artwork|billboard|memorial",
    "geometry": r"misalign|re-?align|alignment|building shapes|offset|orthoimagery|aerial|imagery|satellite|polygon|split|merge|glued|terraced|perimeter|boundary|position of|exact (location|position)",
    "data_quality": r"category|poi types|\btagged\b|tagging|tag this|should be tagged|relation|duplicate|same id|incorrectly labeled|should not have names",
    "import_followup": r"import|canvec|geobase|city data|open data|interpolation|osb id",
}

STATE = {
    "survey_requested": r"\bsurvey\b|re-?survey|surveying|#surveyme|needs? a look|would benefit from a look|please (confirm|check|verify|review|double-check)|double-?check|check (if|where|whether)|is (this|it|there)( still|.*\?)|requires survey|verification|not sure if",
    "app_streetcomplete": r"via streetcomplete",
    "app_organicmaps": r"#organicmaps|organic maps|#comaps|comaps user",
    "app_everydoor": r"#everydoor",
    "has_element_link": r"osm\.org/(node|way|relation)/\d+|openstreetmap\.org/(node|way|relation)/\d+",
    "has_photo": r"attached photo|\.jpe?g\b|\.png\b",
    "has_source_link": r"https?://(?!osm\.org|www\.openstreetmap\.org|streetcomplete\.app|westnordost\.de)",
    "planned_change": r"coming soon|\bfuture\b|\bplanned\b|\bproposed\b|to be (demolished|installed|upgraded|built)|estimated completion|will (be|need|have)|slated|planned for|coming 20\d\d",
    "strict_tag_surveyme": r"#surveyme\b|#resurvey\b",
    "strict_tag_survey_after": r"#survey-?after[ :]*\d{4}-\d{2}(-\d{2})?",
    "note_to_self": r"note to self",
}

MONTHS = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"
DATE_PATTERNS = [
    (re.compile(r"\b(20\d\d)-(\d\d)(?:-(\d\d))?\b"), lambda m: (int(m[1]), int(m[2]), int(m[3] or 1))),
    (re.compile(r"\b(" + MONTHS + r")\.? (\d{1,2}),? (20\d\d)\b"), lambda m: (int(m[3]), _month(m[1]), int(m[2]))),
    (re.compile(r"\b(" + MONTHS + r")\.? (20\d\d)\b"), lambda m: (int(m[2]), _month(m[1]), 1)),
    (re.compile(r"\b(spring|summer|fall|autumn|winter) (20\d\d)\b"), lambda m: (int(m[2]), {"spring": 4, "summer": 7, "fall": 10, "autumn": 10, "winter": 1}[m[1]], 1)),
    (re.compile(r"\b(?:by|in|until|for) (20\d\d)\b"), lambda m: (int(m[1]), 12, 31)),
]


def _month(s):
    s = s[:3].lower()
    return ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"].index(s) + 1


def future_dates(text):
    out = []
    for pat, conv in DATE_PATTERNS:
        for m in pat.finditer(text):
            try:
                y, mo, d = conv(m)
                dt = datetime.date(y, mo, min(d, 28))
            except (ValueError, IndexError):
                continue
            if dt > TODAY:
                out.append(dt)
    return out


def strip_boilerplate(text):
    t = text.lower()
    for b in BOILERPLATE:
        t = re.sub(b, " ", t)
    return t


def load(path="analysis/toronto_open_notes.json"):
    return json.load(open(path, encoding="utf-8"))["features"]


def classify(feature):
    p = feature["properties"]
    comments = p["comments"]
    first_raw = comments[0]["text"] if comments else ""
    full_raw = " \n ".join(c["text"] for c in comments)
    first, full = strip_boilerplate(first_raw), strip_boilerplate(full_raw)
    created = datetime.date.fromisoformat(p["date_created"][:10])
    last = datetime.date.fromisoformat(comments[-1]["date"][:10]) if comments else created
    age = (TODAY - created).days
    since_last = (TODAY - last).days

    topics = {k for k, pat in TOPICS.items() if re.search(pat, first)}
    state = {k for k, pat in STATE.items() if re.search(pat, full_raw.lower() if k.startswith(("app_", "has_", "strict_")) else full)}
    if len(comments) > 1:
        state.add("discussed")
    if "user" not in comments[0]:
        state.add("anonymous")
    if age > 730 and since_last > 365:
        state.add("stale")
    if future_dates(full):
        state.add("future_dated")
    app = bool(state & {"app_streetcomplete", "app_organicmaps", "app_everydoor"})
    words = len(re.findall(r"\w+", first))
    if words <= 3 and not topics and not app:
        state.add("low_signal")

    # App reports: did the reporter already observe the answer, or are they asking?
    if app:
        # StreetComplete free text follows the last ": " after the quest; OM text is the quoted part.
        m = re.search(r"via streetcomplete[^:]*:\s*(.*)", first_raw, re.S | re.I)
        free = m.group(1) if m else (re.match(r'\s*"([^"]*)"', first_raw) or [None, first_raw])[1]
        free_l = (free or "").strip().lower()
        if not free_l or "?" in free_l or re.search(r"not sure|unsure|maybe|might|could be|possibly", free_l):
            state.add("app_needs_ground_check")
        else:
            state.add("app_observed_needs_edit")

    # ---- audience -------------------------------------------------------------
    aud = set()
    if "app_observed_needs_edit" in state or "geometry" in topics:
        aud.add("telemapper")
    if state & {"has_source_link"} and "survey_requested" not in state:
        aud.add("telemapper")
    if state & {"survey_requested", "app_needs_ground_check"} or topics & {"address", "cycling_walking", "amenity_detail", "accessibility"}:
        aud.add("surveyor")
    if "business" in topics and "app_observed_needs_edit" not in state:
        aud.add("surveyor")
    if topics & {"transit", "roads", "data_quality", "import_followup"}:
        aud.add("josm")
    if not aud:
        aud.add("unassigned")
    lon, lat = feature["geometry"]["coordinates"]
    return dict(id=p["id"], age=age, topics=topics, state=state, audience=aud, lat=lat, lon=lon,
                user=comments[0].get("user", ""), created=created, first=re.sub(r"\s+", " ", first_raw)[:120])


def mark_clusters(rows, radius_m=150):
    """Same author, same day, similar opening words, within radius -> one cluster."""
    def key(r):
        return (r["user"], r["created"], " ".join(re.findall(r"\w+", r["first"].lower())[:4]))
    groups = collections.defaultdict(list)
    for r in rows:
        groups[key(r)].append(r)
    for g in groups.values():
        if len(g) < 2:
            continue
        for r in g:
            near = [o for o in g if o is not r and _dist(r, o) <= radius_m * 4]
            if near:
                r["state"].add("duplicate_cluster")


def _dist(a, b):
    dlat = (a["lat"] - b["lat"]) * 111320
    dlon = (a["lon"] - b["lon"]) * 111320 * math.cos(math.radians(a["lat"]))
    return math.hypot(dlat, dlon)


def main(argv):
    rows = [classify(f) for f in load()]
    mark_clusters(rows)
    n = len(rows)
    print(f"{n} open notes\n")
    for axis in ("audience", "topics", "state"):
        c = collections.Counter(k for r in rows for k in r[axis])
        print(f"== {axis} ==")
        for k, v in c.most_common():
            print(f"  {k:26} {v:4}  {100*v//n:3}%")
        print()
    notopic = [r for r in rows if not r["topics"]]
    print(f"no topic: {len(notopic)} ({100*len(notopic)//n}%)")
    if "--unclassified" in argv:
        for r in notopic:
            print(f"  {r['id']} {sorted(r['state'])} | {r['first']}")
    if "--show" in argv:
        cls = argv[argv.index("--show") + 1]
        for r in rows:
            if cls in r["topics"] or cls in r["state"] or cls in r["audience"]:
                print(f"  {r['id']} | {r['first']}")


if __name__ == "__main__":
    main(sys.argv[1:])
