<p align="center">
  <img src="docs/icon.png" alt="Licht icon" width="128" height="128">
</p>

<h1 align="center">Licht</h1>

Android app with a Quick Settings tile that shows the next Shabbos candle lighting and when Shabbos ends, plus a page of past and future weeks.

### [Download the latest signed APK](https://github.com/DanielCohen002/licht/releases/latest)

Package name `com.djcohen.licht`. Needs Android 11 or newer.

What it does:

- **Tile:** "Candles 4:22 / Ends Sat 5:22" for the nearest upcoming Friday and Saturday. It moves to the next week once Shabbos ends. Tap it to open the page.
- **Mini tile:** "Licht mini" is a second tile for the half-size slot (Android 17 lets a tile take half the width). A half-size tile shows only its icon, so Licht mini draws the two times in the icon. If you stretch it to full size it says "Use Licht tile for full size". Android doesn't tell an app which size a tile is, so this is two tiles instead of one that switches.
- **Page:** scrolling weeks with English and Hebrew dates, sunset and the weekly parsha. The upcoming week is highlighted.
- **Times from:** Chabad.org's public zmanim feed (18 minutes before sunset, rounded) or Hebcal's Shabbat API, switchable in Settings. The parsha comes from Hebcal in both modes. If the network is down, the app falls back to a local sun calculation that matches Chabad's rules (candles 18 minutes before sunset, Shabbos ends at 8.5 degrees).
- **Jerusalem:** candles are 40 minutes before sunset there, which is what both sources give. A Settings option switches between 40 and 18 minutes.
- **Location:** follows your phone (coarse location, turned into a ZIP on the device), or pick a saved place, or search by city anywhere in the world or by US ZIP. Hold a saved place to remove it. Cities outside the US use Chabad.org when it lists them (about 900 non-US cities, matched by name) and Hebcal otherwise. If a place is in a different time zone than your phone, a note says the times are in that place's time.
- **Private:** only the INTERNET and coarse location permissions. No account, no analytics.

ZIP codes are US only.

## Install

Download the APK from the [Releases](../../releases/latest) page and install it. Then add the tile: swipe down twice, tap the pencil, and drag "Licht" (full size) or "Licht mini" (half size) into your tiles.

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

## License

GPL-3.0. See [LICENSE](LICENSE). Free software: you can use, change and share it, and anything you share based on it must stay GPL-3.0 with its source available.
