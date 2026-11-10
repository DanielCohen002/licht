<p align="center"><img src="docs/icon.png" alt="Licht icon" width="128"></p>

<h1 align="center">Licht</h1>

Android app with a Quick Settings tile that shows the next Shabbos candle lighting and when Shabbos ends, plus a page of past and future weeks.

- **Tile:** "Candles 4:22 / Ends Sat 5:22" for the nearest upcoming Friday and Saturday. It moves to the next week once Shabbos ends. Tap it to open the page.
- **Page:** scrolling weeks with English and Hebrew dates, sunset and the weekly parsha. The upcoming week is highlighted.
- **Times from:** Chabad.org's public zmanim feed (18 minutes before sunset, rounded) or Hebcal's Shabbat API, switchable in Settings. The parsha comes from Hebcal in both modes. If the network is down, the app falls back to a local sun calculation that matches Chabad's rules (candles 18 minutes before sunset, Shabbos ends at 8.5 degrees).
- **Location:** follows your phone (coarse location, turned into a ZIP on the device), or pick a saved place, or search by US city or ZIP. Hold a saved place to remove it.
- **Private:** only the INTERNET and coarse location permissions. No account, no analytics.

US places only, because Chabad.org times are looked up by ZIP code.

## Install

Download the APK from the [Releases](../../releases) page and install it. Then add the tile: swipe down twice, tap the pencil, and drag "Licht" into your tiles.

## Build

```
./gradlew assembleDebug
```

For a signed release build, put a `signing.properties` file in the project root (it is gitignored):

```
STORE_FILE=C:/path/to/release.jks
STORE_PASSWORD=...
KEY_ALIAS=licht
KEY_PASSWORD=...
```

then run `./gradlew assembleRelease`.

## Data sources

- [Chabad.org zmanim feed](https://www.chabad.org/tools/rss/zmanim.xml) (public RSS). Times belong to Chabad.org.
- [Hebcal Shabbat API](https://www.hebcal.com/home/developer-apis) for the parsha and the Hebcal option.
- [Open-Meteo geocoding](https://open-meteo.com/en/docs/geocoding-api) for city search.

Times are calculated for a ZIP code and are not a substitute for your local rabbi's guidance. Do not rely on them to the last minute.
