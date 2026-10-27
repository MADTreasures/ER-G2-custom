#!/usr/bin/env python3
"""Build catalog.json, the map download catalog of the G2 offline navigation app.

Source
------
https://download.mapsforge.org/maps/v5/ serves Apache directory listings: one
folder per continent (the catalog "groups") holding mapsforge ``.map`` files,
plus sub-folders with the regions of large countries (``europe/germany/``,
``north-america/us/`` ...).  The tree is crawled recursively.  Listing sizes
are rounded ("319M") and listing times are server-local, so every ``.map`` is
requested once with ``Range: bytes=0-63``:

* ``Content-Range: bytes 0-63/<total>`` gives the exact size,
* ``Last-Modified`` gives the date, stored as UTC ``YYYY-MM-DD``,
* the 64 bytes are the start of the mapsforge binary map header (big-endian):
  20-byte magic ``mapsforge binary OSM``, int32 header size, int32 file
  version, int64 file size, int64 creation date (ms since epoch), then the
  bounding box as four int32 microdegrees minLat, minLon, maxLat, maxLon.
  The magic is checked and the header's file size must equal the HTTP size;
  mismatches are reported (the HTTP size is what goes into the catalog).

If the server ignores the range (answers 200), only the first 64 bytes are
read before the connection is dropped, and size and date come from a HEAD
request.  At most 4 requests run in parallel, all with the User-Agent
``G2-Navi-catalog/1.0``; transient errors (timeouts, resets, HTTP 408/425/429/
5xx) are retried with exponential backoff.

Output schema (format "navi-katalog@1", UTF-8, 2-space indent)
--------------------------------------------------------------
{
  "format":    "navi-katalog@1",
  "generated": "YYYY-MM-DD",                         # UTC date of the run
  "mapBase":   "https://download.mapsforge.org/maps/v5/",
  "groups":  [{"id": "europe", "name": "Europa"}, ...],
  "regions": [{"id":     "europe/germany/bayern",    # path below v5/ without ".map"
               "name":   "Bayern",                   # Swiss High German (never "ß")
               "group":  "europe",                   # top folder
               "parent": "europe/germany",           # id of the enclosing folder, or null
               "bytes":  577413521,                  # exact size; null for folder entries
               "date":   "2026-08-28",               # Last-Modified (UTC); null for folders
               "bbox":   [minLat, minLon, maxLat, maxLon]}, ...]   # degrees, 6 decimals
}
Download URL of a region: mapBase + id + ".map".  A sub-folder without a file
of the same name (there is ``us/`` but no ``us.map``) gets a folder entry with
bytes and date null and the union of its children's boxes as bbox.

Order: groups by German name; regions by group (in the order of "groups"),
then parent (top level first, then by the parent's German name), then German
name.  Names compare case-insensitively with accents folded (ä→a, ö→o, ü→u,
é→e ...); ties are broken by id.

Names come from the tables below (GROUP_NAMES, NAMES_BY_ID, NAMES).  A slug
"<base>-<n>" whose base is known becomes "<Name> (Teil <n>)".  Unknown slugs
fall back to title-cased words and are listed in the summary, together with
the REVIEW entries (names that are judgment calls).

Usage:  python3 catalog.py OUT.json
Standard library only.  Progress and a summary go to stderr.  Exit status:
0 complete, 1 catalog written but some maps were left out because their
requests failed, 2 usage error or the directory crawl failed (nothing written).
"""

import concurrent.futures
import email.utils
import html
import http.client
import json
import os
import random
import re
import ssl
import struct
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

MAP_BASE = "https://download.mapsforge.org/maps/v5/"
FORMAT = "navi-katalog@1"
USER_AGENT = "G2-Navi-catalog/1.0"
MAX_PARALLEL = 4          # never more requests than this in flight
TIMEOUT = 60              # seconds per request
ATTEMPTS = 5              # first try + 4 retries
TRANSIENT_STATUS = {408, 425, 429, 500, 502, 503, 504}
MAX_DEPTH = 6             # sanity limit for the folder crawl

MAGIC = b"mapsforge binary OSM"
HEADER = struct.Struct(">iiqqiiii")   # header size, version, file size, creation ms, bbox

# --------------------------------------------------------------------------
# German names (Swiss High German: "ss", never "ß").  Only Latin-1 letters are
# used (no macrons such as Kyūshū) so that the watch font can show every name.
# --------------------------------------------------------------------------

GROUP_NAMES = {
    "africa": "Afrika",
    "asia": "Asien",
    "australia-oceania": "Australien und Ozeanien",
    "central-america": "Mittelamerika",
    "europe": "Europa",
    "north-america": "Nordamerika",
    "russia": "Russland",
    "south-america": "Südamerika",
    "world": "Welt",
}

# Full-id overrides: checked before NAMES, for slugs that mean different places.
NAMES_BY_ID = {
    "world/world": "Welt (Übersicht)",
    "north-america/us/georgia": "Georgia",          # the US state (europe/georgia = Georgien)
}

NAMES = {
    # --- africa ---
    "algeria": "Algerien",
    "angola": "Angola",
    "benin": "Benin",
    "botswana": "Botswana",
    "burkina-faso": "Burkina Faso",
    "burundi": "Burundi",
    "cameroon": "Kamerun",
    "canary-islands": "Kanarische Inseln",
    "cape-verde": "Kap Verde",
    "central-african-republic": "Zentralafrikanische Republik",
    "chad": "Tschad",
    "comores": "Komoren",
    "congo-brazzaville": "Kongo (Republik)",
    "congo-democratic-republic": "Kongo (Demokratische Republik)",
    "djibouti": "Dschibuti",
    "egypt": "Ägypten",
    "equatorial-guinea": "Äquatorialguinea",
    "eritrea": "Eritrea",
    "ethiopia": "Äthiopien",
    "gabon": "Gabun",
    "ghana": "Ghana",
    "guinea": "Guinea",
    "guinea-bissau": "Guinea-Bissau",
    "ivory-coast": "Elfenbeinküste",
    "kenya": "Kenia",
    "lesotho": "Lesotho",
    "liberia": "Liberia",
    "libya": "Libyen",
    "madagascar": "Madagaskar",
    "malawi": "Malawi",
    "mali": "Mali",
    "mauritania": "Mauretanien",
    "mauritius": "Mauritius",
    "morocco": "Marokko",
    "mozambique": "Mosambik",
    "namibia": "Namibia",
    "niger": "Niger",
    "nigeria": "Nigeria",
    "rwanda": "Ruanda",
    "saint-helena-ascension-and-tristan-da-cunha": "St. Helena, Ascension und Tristan da Cunha",
    "sao-tome-and-principe": "São Tomé und Príncipe",
    "senegal-and-gambia": "Senegal und Gambia",
    "seychelles": "Seychellen",
    "sierra-leone": "Sierra Leone",
    "somalia": "Somalia",
    "south-africa-and-lesotho": "Südafrika und Lesotho",
    "south-sudan": "Südsudan",
    "sudan": "Sudan",
    "swaziland": "Eswatini",
    "tanzania": "Tansania",
    "togo": "Togo",
    "tunisia": "Tunesien",
    "uganda": "Uganda",
    "zambia": "Sambia",
    "zimbabwe": "Simbabwe",

    # --- asia ---
    "afghanistan": "Afghanistan",
    "armenia": "Armenien",
    "azerbaijan": "Aserbaidschan",
    "bangladesh": "Bangladesch",
    "bhutan": "Bhutan",
    "cambodia": "Kambodscha",
    "east-timor": "Osttimor",
    "gcc-states": "Arabische Golfstaaten",
    "iran": "Iran",
    "iraq": "Irak",
    "israel-and-palestine": "Israel und Palästina",
    "jordan": "Jordanien",
    "kazakhstan": "Kasachstan",
    "kyrgyzstan": "Kirgisistan",
    "laos": "Laos",
    "lebanon": "Libanon",
    "malaysia-singapore-brunei": "Malaysia, Singapur und Brunei",
    "maldives": "Malediven",
    "mongolia": "Mongolei",
    "myanmar": "Myanmar",
    "nepal": "Nepal",
    "north-korea": "Nordkorea",
    "pakistan": "Pakistan",
    "philippines": "Philippinen",
    "south-korea": "Südkorea",
    "sri-lanka": "Sri Lanka",
    "syria": "Syrien",
    "taiwan": "Taiwan",
    "tajikistan": "Tadschikistan",
    "thailand": "Thailand",
    "turkmenistan": "Turkmenistan",
    "uzbekistan": "Usbekistan",
    "vietnam": "Vietnam",
    "yemen": "Jemen",
    # asia/china/ (folder only, there is no china.map)
    "china": "China",
    "anhui": "Anhui",
    "beijing": "Peking",
    "chongqing": "Chongqing",
    "fujian": "Fujian",
    "gansu": "Gansu",
    "guangdong": "Guangdong",
    "guangxi": "Guangxi",
    "guizhou": "Guizhou",
    "hainan": "Hainan",
    "hebei": "Hebei",
    "heilongjiang": "Heilongjiang",
    "henan": "Henan",
    "hong-kong": "Hongkong",
    "hubei": "Hubei",
    "hunan": "Hunan",
    "inner-mongolia": "Innere Mongolei",
    "jiangsu": "Jiangsu",
    "jiangxi": "Jiangxi",
    "jilin": "Jilin",
    "liaoning": "Liaoning",
    "macau": "Macau",
    "ningxia": "Ningxia",
    "qinghai": "Qinghai",
    "shaanxi": "Shaanxi",
    "shandong": "Shandong",
    "shanghai": "Shanghai",
    "shanxi": "Shanxi",
    "sichuan": "Sichuan",
    "tianjin": "Tianjin",
    "tibet": "Tibet",
    "xinjiang": "Xinjiang",
    "yunnan": "Yunnan",
    "zhejiang": "Zhejiang",
    # asia/india/
    "india": "Indien",
    "central-zone": "Indien Zentral",
    "eastern-zone": "Indien Ost",
    "north-eastern-zone": "Indien Nordost",
    "northern-zone": "Indien Nord",
    "southern-zone": "Indien Süd",
    "western-zone": "Indien West",
    # asia/indonesia/
    "indonesia": "Indonesien",
    "java": "Java",
    "kalimantan": "Kalimantan",
    "maluku": "Molukken",
    "nusa-tenggara": "Kleine Sundainseln",
    "papua": "Papua",
    "sulawesi": "Sulawesi",
    "sumatra": "Sumatra",
    # asia/japan/
    "japan": "Japan",
    "chubu": "Chubu",
    "chugoku": "Chugoku",
    "hokkaido": "Hokkaido",
    "kansai": "Kansai",
    "kanto": "Kanto",
    "kyushu": "Kyushu",
    "shikoku": "Shikoku",
    "tohoku": "Tohoku",

    # --- australia-oceania ("-1", "-2" ... files become "(Teil n)") ---
    "american-oceania": "Amerikanisch-Ozeanien",
    "australia": "Australien",
    "cook-islands": "Cookinseln",
    "fiji": "Fidschi",
    "ile-de-clipperton": "Clipperton-Insel",
    "kiribati": "Kiribati",
    "marshall-islands": "Marshallinseln",
    "micronesia": "Mikronesien",
    "nauru": "Nauru",
    "new-caledonia": "Neukaledonien",
    "new-zealand": "Neuseeland",
    "niue": "Niue",
    "palau": "Palau",
    "papua-new-guinea": "Papua-Neuguinea",
    "pitcairn-islands": "Pitcairninseln",
    "polynesie-francaise": "Französisch-Polynesien",
    "samoa": "Samoa",
    "solomon-islands": "Salomonen",
    "tokelau": "Tokelau",
    "tonga": "Tonga",
    "tuvalu": "Tuvalu",
    "vanuatu": "Vanuatu",
    "wallis-et-futuna": "Wallis und Futuna",

    # --- central-america ---
    "bahamas": "Bahamas",
    "belize": "Belize",
    "costa-rica": "Costa Rica",
    "cuba": "Kuba",
    "el-salvador": "El Salvador",
    "guatemala": "Guatemala",
    "haiti-and-domrep": "Haiti und Dominikanische Republik",
    "honduras": "Honduras",
    "jamaica": "Jamaika",
    "nicaragua": "Nicaragua",
    "panama": "Panama",

    # --- europe ---
    "albania": "Albanien",
    "andorra": "Andorra",
    "austria": "Österreich",
    "azores": "Azoren",
    "belarus": "Belarus",
    "belgium": "Belgien",
    "bosnia-herzegovina": "Bosnien und Herzegowina",
    "bulgaria": "Bulgarien",
    "croatia": "Kroatien",
    "cyprus": "Zypern",
    "czech-republic": "Tschechien",
    "denmark": "Dänemark",
    "estonia": "Estland",
    "faroe-islands": "Färöer",
    "finland": "Finnland",
    "georgia": "Georgien",
    "great-britain": "Grossbritannien",
    "greece": "Griechenland",
    "guernsey-jersey": "Guernsey und Jersey",
    "hungary": "Ungarn",
    "iceland": "Island",
    "ireland-and-northern-ireland": "Irland und Nordirland",
    "isle-of-man": "Isle of Man",
    "kosovo": "Kosovo",
    "latvia": "Lettland",
    "liechtenstein": "Liechtenstein",
    "lithuania": "Litauen",
    "luxembourg": "Luxemburg",
    "macedonia": "Nordmazedonien",
    "malta": "Malta",
    "moldova": "Moldau",
    "monaco": "Monaco",
    "montenegro": "Montenegro",
    "norway": "Norwegen",
    "poland": "Polen",
    "portugal": "Portugal",
    "romania": "Rumänien",
    "serbia": "Serbien",
    "slovakia": "Slowakei",
    "slovenia": "Slowenien",
    "sweden": "Schweden",
    "switzerland": "Schweiz",
    "turkey": "Türkei",
    "ukraine": "Ukraine",
    # europe/france/ (former regions, before the 2016 merger)
    "france": "Frankreich",
    "alsace": "Elsass",
    "aquitaine": "Aquitanien",
    "auvergne": "Auvergne",
    "basse-normandie": "Basse-Normandie",
    "bourgogne": "Burgund",
    "bretagne": "Bretagne",
    "centre": "Centre-Val de Loire",
    "champagne-ardenne": "Champagne-Ardenne",
    "corse": "Korsika",
    "franche-comte": "Franche-Comté",
    "guadeloupe": "Guadeloupe",
    "guyane": "Französisch-Guayana",
    "haute-normandie": "Haute-Normandie",
    "ile-de-france": "Île-de-France",
    "languedoc-roussillon": "Languedoc-Roussillon",
    "limousin": "Limousin",
    "lorraine": "Lothringen",
    "martinique": "Martinique",
    "mayotte": "Mayotte",
    "midi-pyrenees": "Midi-Pyrénées",
    "nord-pas-de-calais": "Nord-Pas-de-Calais",
    "pays-de-la-loire": "Pays de la Loire",
    "picardie": "Picardie",
    "poitou-charentes": "Poitou-Charentes",
    "provence-alpes-cote-d-azur": "Provence-Alpes-Côte d'Azur",
    "reunion": "Réunion",
    "rhone-alpes": "Rhône-Alpes",
    # europe/germany/
    "germany": "Deutschland",
    "baden-wuerttemberg": "Baden-Württemberg",
    "bayern": "Bayern",
    "berlin": "Berlin",
    "brandenburg": "Brandenburg",
    "bremen": "Bremen",
    "hamburg": "Hamburg",
    "hessen": "Hessen",
    "mecklenburg-vorpommern": "Mecklenburg-Vorpommern",
    "niedersachsen": "Niedersachsen",
    "nordrhein-westfalen": "Nordrhein-Westfalen",
    "rheinland-pfalz": "Rheinland-Pfalz",
    "saarland": "Saarland",
    "sachsen": "Sachsen",
    "sachsen-anhalt": "Sachsen-Anhalt",
    "schleswig-holstein": "Schleswig-Holstein",
    "thueringen": "Thüringen",
    # europe/italy/
    "italy": "Italien",
    "centro": "Mittelitalien",
    "isole": "Sizilien und Sardinien",
    "nord-est": "Nordostitalien",
    "nord-ovest": "Nordwestitalien",
    "sud": "Süditalien",
    "lombardia": "Lombardei",          # not on the server at the moment
    # europe/netherlands/
    "netherlands": "Niederlande",
    "drenthe": "Drenthe",
    "flevoland": "Flevoland",
    "friesland": "Friesland",
    "gelderland": "Gelderland",
    "groningen": "Groningen",
    "limburg": "Limburg",
    "noord-brabant": "Nordbrabant",
    "noord-holland": "Nordholland",
    "overijssel": "Overijssel",
    "utrecht": "Utrecht",
    "zeeland": "Zeeland",
    "zuid-holland": "Südholland",
    # europe/spain/
    "spain": "Spanien",
    "andalucia": "Andalusien",
    "aragon": "Aragonien",
    "asturias": "Asturien",
    "cantabria": "Kantabrien",
    "castilla-la-mancha": "Kastilien-La Mancha",
    "castilla-y-leon": "Kastilien und León",
    "cataluna": "Katalonien",
    "ceuta": "Ceuta",
    "extremadura": "Extremadura",
    "galicia": "Galicien",
    "islas-baleares": "Balearen",
    "la-rioja": "La Rioja",
    "madrid": "Madrid",
    "melilla": "Melilla",
    "murcia": "Murcia",
    "navarra": "Navarra",
    "pais-vasco": "Baskenland",
    "valencia": "Valencia",
    # europe/united-kingdom/ (folder only)
    "united-kingdom": "Vereinigtes Königreich",
    "england": "England",
    "scotland": "Schottland",
    "wales": "Wales",

    # --- north-america ---
    "greenland": "Grönland",
    "mexico": "Mexiko",
    "us-midwest": "USA Mittlerer Westen",
    "us-northeast": "USA Nordost",
    "us-south": "USA Süd",
    "us-west": "USA West",
    # north-america/canada/ (folder only)
    "canada": "Kanada",
    "alberta": "Alberta",
    "british-columbia": "British Columbia",
    "manitoba": "Manitoba",
    "new-brunswick": "New Brunswick",
    "newfoundland-and-labrador": "Neufundland und Labrador",
    "northwest-territories": "Nordwest-Territorien",
    "nova-scotia": "Nova Scotia",
    "nunavut": "Nunavut",
    "ontario": "Ontario",
    "prince-edward-island": "Prince Edward Island",
    "quebec": "Québec",
    "saskatchewan": "Saskatchewan",
    "yukon": "Yukon",
    # north-america/us/ (folder only); German usage keeps the English state
    # names except Kalifornien.  "georgia" is handled in NAMES_BY_ID.
    "us": "USA",
    "alabama": "Alabama",
    "alaska": "Alaska",
    "arizona": "Arizona",
    "arkansas": "Arkansas",
    "california": "Kalifornien",
    "colorado": "Colorado",
    "connecticut": "Connecticut",
    "delaware": "Delaware",
    "district-of-columbia": "District of Columbia",
    "florida": "Florida",
    "hawaii": "Hawaii",
    "idaho": "Idaho",
    "illinois": "Illinois",
    "indiana": "Indiana",
    "iowa": "Iowa",
    "kansas": "Kansas",
    "kentucky": "Kentucky",
    "louisiana": "Louisiana",
    "maine": "Maine",
    "maryland": "Maryland",
    "massachusetts": "Massachusetts",
    "michigan": "Michigan",
    "minnesota": "Minnesota",
    "mississippi": "Mississippi",
    "missouri": "Missouri",
    "montana": "Montana",
    "nebraska": "Nebraska",
    "nevada": "Nevada",
    "new-hampshire": "New Hampshire",
    "new-jersey": "New Jersey",
    "new-mexico": "New Mexico",
    "new-york": "New York",
    "north-carolina": "North Carolina",
    "north-dakota": "North Dakota",
    "ohio": "Ohio",
    "oklahoma": "Oklahoma",
    "oregon": "Oregon",
    "pennsylvania": "Pennsylvania",
    "puerto-rico": "Puerto Rico",
    "rhode-island": "Rhode Island",
    "south-carolina": "South Carolina",
    "south-dakota": "South Dakota",
    "tennessee": "Tennessee",
    "texas": "Texas",
    "us-virgin-islands": "Amerikanische Jungferninseln",
    "utah": "Utah",
    "vermont": "Vermont",
    "virginia": "Virginia",
    "washington": "Washington",
    "west-virginia": "West Virginia",
    "wisconsin": "Wisconsin",
    "wyoming": "Wyoming",

    # --- russia (federal districts, short forms) ---
    "central-fed-district": "Zentralrussland",
    "crimean-fed-district": "Krim",
    "far-eastern-fed-district": "Ferner Osten",
    "kaliningrad": "Kaliningrad",
    "north-caucasus-fed-district": "Nordkaukasus",
    "northwestern-fed-district": "Nordwestrussland",
    "siberian-fed-district": "Sibirien",
    "south-fed-district": "Südrussland",
    "ural-fed-district": "Ural",
    "volga-fed-district": "Wolgaregion",

    # --- south-america ---
    "argentina": "Argentinien",
    "bolivia": "Bolivien",
    "chile": "Chile",
    "colombia": "Kolumbien",
    "ecuador": "Ecuador",
    "guyana": "Guyana",
    "paraguay": "Paraguay",
    "peru": "Peru",
    "suriname": "Suriname",
    "uruguay": "Uruguay",
    "venezuela": "Venezuela",
    # south-america/brazil/
    "brazil": "Brasilien",
    "centro-oeste": "Brasilien Zentral-West",
    "nordeste": "Brasilien Nordost",
    "norte": "Brasilien Nord",
    "sudeste": "Brasilien Südost",
    "sul": "Brasilien Süd",
}

# Names above that are judgment calls rather than established German names;
# listed in the summary so that someone can review them.
REVIEW = {
    "congo-brazzaville", "congo-democratic-republic", "swaziland", "moldova",
    "gcc-states", "nusa-tenggara",
    "central-zone", "eastern-zone", "north-eastern-zone", "northern-zone",
    "southern-zone", "western-zone",
    "american-oceania", "centre", "isole",
    "us-midwest", "us-northeast", "us-south",
    "centro-oeste", "nordeste", "norte", "sudeste", "sul",
    "central-fed-district", "crimean-fed-district", "far-eastern-fed-district",
    "north-caucasus-fed-district", "northwestern-fed-district",
    "siberian-fed-district", "south-fed-district", "ural-fed-district",
    "volga-fed-district",
}

PART_RE = re.compile(r"(?P<base>.+)-(?P<n>\d+)")


def german_name(region_id):
    """Return (name, table key or None) for a region id.

    The key is the NAMES_BY_ID / NAMES entry that produced the name; None means
    the slug is unknown and the name is a title-cased fallback.
    """
    if region_id in NAMES_BY_ID:
        return NAMES_BY_ID[region_id].replace("ß", "ss"), region_id
    slug = region_id.rsplit("/", 1)[-1]
    if slug in NAMES:
        return NAMES[slug].replace("ß", "ss"), slug
    part = PART_RE.fullmatch(slug)
    if part and part["base"] in NAMES:
        return f"{NAMES[part['base']]} (Teil {int(part['n'])})".replace("ß", "ss"), part["base"]
    return " ".join(word.capitalize() for word in slug.split("-")), None


def collate(text):
    """Sort key: case-insensitive, accents folded (ä→a, ö→o, ü→u, é→e ...)."""
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).casefold()


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------

class FetchError(Exception):
    """A request that failed for good (after retries, or not retryable)."""


_lock = threading.Lock()
RETRIED = []   # (url, reason) of every transient error that was retried


def log(message):
    print(message, file=sys.stderr, flush=True)


def _retry_after(headers):
    value = (headers or {}).get("Retry-After", "")
    return min(int(value), 120) if value.strip().isdigit() else None


def fetch(url, method="GET", headers=None, limit=None):
    """Request *url*; return (status, headers, body).

    *limit* reads at most that many body bytes and then drops the connection.
    Transient failures are retried with exponential backoff (about 2, 4, 8,
    16 s, with jitter, or the server's Retry-After); others raise FetchError.
    """
    request_headers = {"User-Agent": USER_AGENT, **(headers or {})}
    for attempt in range(1, ATTEMPTS + 1):
        request = urllib.request.Request(url, headers=request_headers, method=method)
        wait = None
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                body = response.read() if limit is None else response.read(limit)
                return response.status, response.headers, body
        except urllib.error.HTTPError as err:
            err.close()
            if err.code not in TRANSIENT_STATUS:
                raise FetchError(f"{url}: HTTP {err.code}") from None
            reason, wait = f"HTTP {err.code}", _retry_after(err.headers)
        except urllib.error.URLError as err:
            if isinstance(err.reason, ssl.SSLCertVerificationError):
                raise FetchError(f"{url}: {err.reason}") from None
            reason = f"{type(err.reason).__name__}: {err.reason}"
        except (http.client.HTTPException, OSError) as err:   # timeouts, resets
            reason = f"{type(err).__name__}: {err}"
        if attempt == ATTEMPTS:
            raise FetchError(f"{url}: {reason} (gave up after {ATTEMPTS} attempts)")
        if wait is None:
            wait = min(60.0, 2.0 ** attempt) * random.uniform(0.75, 1.25)
        with _lock:
            RETRIED.append((url, reason))
        log(f"  retry {attempt}/{ATTEMPTS - 1} in {wait:.1f} s: {url}: {reason}")
        time.sleep(wait)
    raise AssertionError("unreachable")


# --------------------------------------------------------------------------
# Crawl the listings
# --------------------------------------------------------------------------

HREF_RE = re.compile(r'<a\s[^>]*?href="([^"]*)"', re.IGNORECASE)


def list_directory(rel):
    """Return (sub-folder names, .map names without extension) of MAP_BASE + rel."""
    _, _, body = fetch(MAP_BASE + rel)
    folders, maps = set(), set()
    for href in HREF_RE.findall(body.decode("utf-8", "replace")):
        href = html.unescape(href)
        parts = urllib.parse.urlsplit(href)
        if parts.scheme or parts.netloc or parts.query or not parts.path \
                or href.startswith(("/", ".")):
            continue                        # sort links, parent directory, absolute links
        name = urllib.parse.unquote(parts.path)
        if name.endswith("/") and name.count("/") == 1:
            folders.add(name[:-1])
        elif name.endswith(".map") and "/" not in name:
            maps.add(name[:-len(".map")])
    return sorted(folders), sorted(maps)


def crawl(pool):
    """Walk the listing tree breadth-first.

    Returns (group ids, sub-folder ids, map ids), all relative to MAP_BASE and
    without trailing slash or extension, e.g. "europe", "europe/germany",
    "europe/germany/bayern".
    """
    groups, folders, maps = [], [], []
    level, depth = [""], 0
    while level:
        depth += 1
        if depth > MAX_DEPTH:
            raise FetchError(f"folders nested deeper than {MAX_DEPTH} levels below {MAP_BASE}")
        next_level = []
        for rel, (subs, files) in zip(level, pool.map(list_directory, level)):
            for name in files:
                if rel:
                    maps.append(rel + name)
                else:
                    log(f"  ignoring {name}.map at the top level (it belongs to no group)")
            for name in subs:
                (folders if rel else groups).append(rel + name)
                next_level.append(rel + name + "/")
        level = next_level
    return groups, folders, maps


# --------------------------------------------------------------------------
# Probe the .map files
# --------------------------------------------------------------------------

CONTENT_RANGE_RE = re.compile(r"bytes\s+\d+-\d+/(\d+)\s*", re.IGNORECASE)


def utc_date(value):
    """'Wed, 23 Sep 2026 04:53:23 GMT' -> '2026-09-23' (UTC); None if unusable."""
    if not value:
        return None
    try:
        moment = email.utils.parsedate_to_datetime(value)
    except (TypeError, ValueError, IndexError):
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)
    return moment.astimezone(timezone.utc).date().isoformat()


def parse_header(data):
    """Decode the start of a mapsforge binary map header (raises ValueError)."""
    if len(data) < len(MAGIC) + HEADER.size:
        raise ValueError(f"only {len(data)} header bytes received")
    if data[:len(MAGIC)] != MAGIC:
        raise ValueError(f"bad magic {data[:len(MAGIC)]!r}")
    header_size, version, file_size, created_ms, *box = HEADER.unpack_from(data, len(MAGIC))
    min_lat, min_lon, max_lat, max_lon = (v / 1e6 for v in box)
    if not (-90 <= min_lat <= max_lat <= 90 and -180 <= min_lon <= max_lon <= 180):
        raise ValueError(f"implausible bounding box {box} (microdegrees)")
    return {
        "headerSize": header_size,
        "version": version,
        "fileSize": file_size,
        "created": created_ms,
        "bbox": [round(v / 1e6, 6) for v in box],
    }


def probe_map(map_id):
    """Exact size, UTC date and header of one .map, via one 64-byte range request."""
    url = MAP_BASE + urllib.parse.quote(map_id) + ".map"
    status, headers, head = fetch(url, headers={"Range": "bytes=0-63"}, limit=64)
    total, fallback = None, None
    last_modified = headers.get("Last-Modified")
    if status == 206:
        match = CONTENT_RANGE_RE.fullmatch(headers.get("Content-Range", ""))
        total = int(match.group(1)) if match else None
    if total is None:                       # range ignored or no usable Content-Range
        fallback = f"range request answered HTTP {status} without a total size; used HEAD"
        _, head_headers, _ = fetch(url, method="HEAD")
        length = head_headers.get("Content-Length", "")
        total = int(length) if length.strip().isdigit() else None
        last_modified = head_headers.get("Last-Modified") or last_modified
    try:
        header, header_error = parse_header(head), None
    except ValueError as err:
        header, header_error = None, str(err)
    return {"id": map_id, "bytes": total, "date": utc_date(last_modified),
            "header": header, "headerError": header_error, "fallback": fallback}


def probe_all(pool, map_ids, failures):
    """Probe every map (MAX_PARALLEL at a time); failed ones go to *failures*."""
    results = []
    futures = {pool.submit(probe_map, map_id): map_id for map_id in map_ids}
    for done, future in enumerate(concurrent.futures.as_completed(futures), 1):
        try:
            results.append(future.result())
        except FetchError as err:
            failures.append((futures[future], str(err)))
        if done % 50 == 0 or done == len(futures):
            log(f"  probed {done}/{len(futures)} maps")
    return results


# --------------------------------------------------------------------------
# Build the catalog
# --------------------------------------------------------------------------

def union_bbox(boxes):
    return [min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes)]


def parent_of(region_id):
    """'europe/germany/bayern' -> 'europe/germany'; files directly in a group -> None."""
    parts = region_id.split("/")
    return "/".join(parts[:-1]) if len(parts) > 2 else None


def build_catalog(group_ids, folders, probes, issues, unknown_names, used_keys):
    regions = {}

    def add(region_id, size, date, bbox):
        name, key = german_name(region_id)
        if key is None:
            unknown_names.append((region_id, name))
        else:
            used_keys.add(key)
        regions[region_id] = {
            "id": region_id, "name": name, "group": region_id.split("/", 1)[0],
            "parent": parent_of(region_id), "bytes": size, "date": date, "bbox": bbox,
        }

    for probe in probes:
        map_id, header = probe["id"], probe["header"]
        if probe["bytes"] is None:
            issues.append((map_id, "no size available; left out of the catalog"))
            continue
        if probe["fallback"]:
            issues.append((map_id, probe["fallback"]))
        if probe["headerError"]:
            issues.append((map_id, f"header: {probe['headerError']}; bbox left null"))
        elif header["fileSize"] != probe["bytes"]:
            issues.append((map_id, f"header file size {header['fileSize']} != HTTP size "
                                   f"{probe['bytes']} (catalog uses the HTTP size)"))
        if probe["date"] is None:
            issues.append((map_id, "no usable Last-Modified; date left null"))
        add(map_id, probe["bytes"], probe["date"], header["bbox"] if header else None)

    # Folder entries for sub-folders without a file of the same name; deepest
    # first, so that nested folder entries exist before their parents need them.
    for folder in sorted(folders, key=lambda f: (-f.count("/"), f)):
        if folder in regions:
            continue
        children = [r for r in regions.values() if r["parent"] == folder]
        if not children:
            issues.append((folder, "folder without any map; left out"))
            continue
        boxes = [r["bbox"] for r in children if r["bbox"]]
        add(folder, None, None, union_bbox(boxes) if boxes else None)

    groups = []
    for group_id in group_ids:
        if not any(r["group"] == group_id for r in regions.values()):
            continue
        name = GROUP_NAMES.get(group_id)
        if name is None:
            name = " ".join(word.capitalize() for word in group_id.split("-"))
            unknown_names.append((group_id, name))
        groups.append({"id": group_id, "name": name.replace("ß", "ss")})
    groups.sort(key=lambda g: (collate(g["name"]), g["id"]))
    rank = {g["id"]: i for i, g in enumerate(groups)}

    def region_key(region):
        parent = region["parent"]
        if parent is None:
            parent_key = (0, "", "")
        else:
            parent_key = (1, collate(regions.get(parent, {"name": parent})["name"]), parent)
        return (rank[region["group"]], parent_key, collate(region["name"]), region["name"],
                region["id"])

    return {
        "format": FORMAT,
        "generated": datetime.now(timezone.utc).date().isoformat(),
        "mapBase": MAP_BASE,
        "groups": groups,
        "regions": sorted(regions.values(), key=region_key),
    }


def to_json(catalog):
    """2-space indented JSON; each 4-number bbox is kept on one line."""
    text = json.dumps(catalog, ensure_ascii=False, indent=2)
    number = r"(-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)"
    text = re.sub(r"\[\s+" + r",\s+".join([number] * 4) + r"\s+\]", r"[\1, \2, \3, \4]", text)
    return text + "\n"


def human(size):
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if size < 1024 or unit == "TiB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024


def summary(catalog, failures, issues, unknown_names, used_keys):
    regions = catalog["regions"]
    files = [r for r in regions if r["bytes"] is not None]
    total = sum(r["bytes"] for r in files)
    log("")
    log(f"groups:  {len(catalog['groups'])}")
    log(f"regions: {len(regions)} ({len(files)} map files + {len(regions) - len(files)} folder entries)")
    log(f"total map bytes: {total} ({human(total)})")
    log("10 biggest regions:")
    for r in sorted(files, key=lambda r: (-r["bytes"], r["id"]))[:10]:
        log(f"  {r['bytes']:>13}  {human(r['bytes']):>9}  {r['id']}  ({r['name']})")
    log(f"requests retried after transient errors: {len(RETRIED)}")
    for url, reason in RETRIED:
        log(f"  {url}: {reason}")
    log(f"failed requests (maps left out): {len(failures)}")
    for map_id, reason in sorted(failures):
        log(f"  {map_id}: {reason}")
    log(f"header mismatches and other problems: {len(issues)}")
    for region_id, message in sorted(issues):
        log(f"  {region_id}: {message}")
    log(f"unknown slugs (title-cased fallback names): {len(unknown_names)}")
    for region_id, name in sorted(unknown_names):
        log(f"  {region_id} -> {name}")
    review = sorted(key for key in REVIEW if key in used_keys)
    log(f"names to review (judgment calls): {len(review)}")
    for key in review:
        log(f"  {key} -> {NAMES_BY_ID.get(key) or NAMES[key]}")


def main(argv):
    if len(argv) != 2 or argv[1] in ("-h", "--help"):
        log("usage: python3 catalog.py OUT.json")
        return 2
    out_path = argv[1]
    failures, issues, unknown_names, used_keys = [], [], [], set()
    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_PARALLEL) as pool:
        log(f"crawling {MAP_BASE} ...")
        try:
            group_ids, folders, map_ids = crawl(pool)
        except FetchError as err:
            log(f"error: directory crawl failed: {err}")
            return 2
        log(f"  {len(map_ids)} .map files, {len(group_ids)} groups, {len(folders)} sub-folders")
        log("reading the first 64 bytes of every map ...")
        probes = probe_all(pool, map_ids, failures)
    catalog = build_catalog(group_ids, folders, probes, issues, unknown_names, used_keys)
    tmp_path = out_path + ".tmp"
    with open(tmp_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(to_json(catalog))
    os.replace(tmp_path, out_path)
    log(f"wrote {out_path}")
    summary(catalog, failures, issues, unknown_names, used_keys)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
