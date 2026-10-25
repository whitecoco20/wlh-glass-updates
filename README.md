# WLH Glass

A fast, ad-free "liquid glass" home screen for Google TV and Android TV boxes. No root. Nothing on your TV gets turned off.

![WLH Glass home screen](docs/screenshot.jpg)

Built and tested on an **onn. Full HD Streaming Device** (Google TV, Android 14). It should work on other Google TV / Android TV boxes running Android 9 or newer.

## What you get

- **Your apps, no ads.** A glass row of 8 favorite apps with their real icons, plus an All apps screen. No sponsored rows, no trailers.
- **Live weather.** Current temperature, feels-like, high/low and a 3-day forecast (Open-Meteo, no account needed). US cities also show National Weather Service alerts.
- **Your own backgrounds.** A built-in sunset scene, plus any photos you drop in a folder on the TV. Flip with one button or rotate every 15 minutes.
- **Home button takeover.** An optional switch makes the remote's Home button and power-on open WLH Glass. Turn it off any time to get Google TV back.
- **Sleep timer.** 15 to 120 minutes, keeps counting while you watch other apps.
- **Screensaver.** Your background with a big clock and the weather.
- **Auto-update.** Settings › App update finds and installs new versions (you confirm on screen).
- **Light.** About 35–45 MB of memory. Low power mode turns the blur off.

## Install (no PC needed)

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. In Downloader, open this link and install:
   `https://raw.githubusercontent.com/whitecoco20/wlh-glass-updates/main/releases/wlh-glass-1.7.apk`
   Allow Downloader to install apps when Android asks. If Play Protect warns you, choose **Install anyway**.
3. Open **WLH Glass** from your apps.

### Install from a Windows PC instead

See [`windows/install.bat`](windows/install.bat). You need Google's platform-tools (adb), USB debugging turned on in the TV's Developer options, and your TV's IP address.

## Make Home open WLH Glass

On Google TV, Android ignores the normal "default home app" setting, and the only way to replace Google TV's home is to disable it, which has left some Google TV devices stuck restarting. WLH Glass does **not** do that. It uses a small accessibility service that only reacts to the Home key.

1. In WLH Glass go to **Settings › Home button** and press OK. Android opens the Accessibility screen.
2. Select **WLH Glass Home button** and turn it **On**.
   If it's greyed out ("restricted setting"): Settings › Apps › WLH Glass › ⋮ › **Allow restricted settings**, then try again.
3. Press Home. You land on WLH Glass. It also opens a few seconds after the TV starts.

To go back to Google TV: **WLH Glass › Settings › Home button › OK**.

**Don't "Force stop" WLH Glass.** Android turns accessibility switches off when an app is force-stopped.

## Backgrounds

Put `.jpg`, `.png` or `.webp` files (landscape, 1920×1080 is ideal) in:

```
/sdcard/Android/data/com.wlh.glass/files/backgrounds/
```

Use a file manager on the TV, or `windows/add-backgrounds.bat`, or:

```
adb push myphoto.jpg /sdcard/Android/data/com.wlh.glass/files/backgrounds/
```

Then press **Background** on the home screen, or pick one (or **Rotate**) in Settings.

## Screensaver

Android TV hides third-party screensavers on many Google TV boxes. To pick WLH Glass from a PC:

```
adb shell settings put secure screensaver_components com.wlh.glass/.GlassDream
```

Back to Google's: `adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop`

## Known limits

- Google TV's own home keeps running in the background as a safety net. You may see it for a moment at power-on or when pressing Home.
- While the Home switch is on, **Amazon Silk** switches to accessibility mode and its free cursor stops working. Switch to Google TV before using Silk (then reopen Silk), or use TV Bro / Downloader's browser.
- "Continue watching" isn't possible: Google TV only shares that list with its own home screen.
- The Home switch can only be turned **on** from the TV's Accessibility settings (Android security rule).

## Customize

- **Favorites:** edit `FAV_PKGS` (and `FAV_BLURB`) in `app/src/com/wlh/glass/GlassView.java`. If fewer than 8 are installed, the row fills from your other apps.
- **Weather cities:** `CITY_NAME`, `CITY_LAT`, `CITY_LON` in the same file.
- **Update feed for your fork:** change `FEED` in `Updater.java` to your repo's `version.json`.

## Build it yourself

No Gradle or Android Studio. On Ubuntu, Debian or Windows WSL:

```
sudo apt install aapt apksigner zipalign android-sdk-platform-23 dalvik-exchange default-jdk
./build.sh
```

The first run creates your own signing key (`glass.keystore`). Keep it safe and never commit it: updates only install over a copy signed with the same key. Your build is a separate app from the releases here unless you change the package name.

## Publishing an update (maintainers)

1. Bump `versionCode`/`versionName` in `app/AndroidManifest.xml`, run `./build.sh`.
2. Copy `build/wlh-glass.apk` to `releases/wlh-glass-X.Y.apk`.
3. Update `version.json` (`versionCode`, `versionName`, `apk` link, `notes`) and push.
   TVs check every 6 hours, or right away from Settings › App update.

## Uninstall

Settings › Apps › WLH Glass › Uninstall, or `windows/uninstall.bat`. Nothing else on the TV was changed, so Google TV's home simply comes back.

## License

MIT, © 2026 Wilhoite Lux Horizon LLC. Weather data by [Open-Meteo](https://open-meteo.com) and the [National Weather Service](https://www.weather.gov).
