# WLH Glass store listing kit

Everything needed to submit WLH Glass to the Amazon Appstore (Fire TV) and Google Play (Google TV / Android TV). Graphics are in this folder.

Privacy policy URL (both stores):
`https://raw.githack.com/whitecoco20/wlh-glass-updates/main/docs/privacy.html`
(Or `https://whitecoco20.github.io/wlh-glass-updates/privacy.html` once GitHub Pages is on.)

---

## Listing text (both stores)

**App name:** WLH Glass

**Short description (80 characters max):**
An ad-free glass home screen for your TV: your apps, weather, photos, profiles.

**Full description:**

Your TV's home screen shouldn't be an ad. WLH Glass is a clean, fast home screen with a frosted "liquid glass" look. Just your apps, your photos and the weather.

YOUR APPS, NO ADS
• Pick your 8 favorite apps with the remote, in your order
• Recently opened: your last 6 apps, one press away
• Hide apps you never use, without uninstalling anything
• Profiles: everyone in the house gets their own favorites, background and colors
• PIN lock: keep chosen apps and Settings behind a 4-digit PIN

WEATHER, TIME AND SCORES
• Weather for any city or country, with a 3-day forecast
• Severe weather alerts for US locations
• The clock follows your weather location's time zone (or your TV's)
• Up to 3 world clocks
• Sports scores for your teams: NFL, NBA, MLB, NHL, WNBA, MLS, Premier League, La Liga and more
• Clock styles: standard, large, minimal or analog

MAKE IT YOURS
• Send photos from your phone by scanning a QR code
• Photo screensaver with a big clock and the weather
• Accent colors, glass blur levels, low power mode
• Sleep timer

TAKE IT WITH YOU
• Scan a QR code to open your setup in any web browser, on a work computer or another TV

LIGHT AND PRIVATE
• About 40 MB of memory
• No accounts, no ads, no tracking
• Nothing on your TV is disabled or removed. Uninstall any time and your TV's home is right where you left it.

**Category:** Amazon: Lifestyle (or Utilities). Google Play: Personalization.

**Keywords / tags:** home screen, launcher, TV launcher, ad-free, weather, clock, screensaver, profiles, parental lock, Fire TV, Google TV

**Contact email:** info@wilhoiteluxhorizonllc.com
**Website:** https://github.com/whitecoco20/wlh-glass-updates

---

## Graphics in this folder

| File | Amazon Appstore | Google Play |
|---|---|---|
| `icon-512.png` | Large icon (512×512) | App icon (512×512) |
| `icon-114.png` | Small icon (114×114) | |
| `screenshot-1-home.jpg`, `screenshot-2-analog.jpg`, `screenshot-3-phone-photos.jpg` | Fire TV screenshots (1920×1080, 3 minimum) | Android TV screenshots |
| `firetv-background-1920x1080.jpg` | Fire TV background image | |
| `feature-graphic-1024x500.png` | Promotional image (optional) | Feature graphic (required) |
| `tv-banner-1280x720.png` | | TV banner (required for TV) |

The screenshots use sample app icons (Movies, Music, Live TV and so on) so they don't show other companies' logos.

---

## Content rating questionnaire

No violence, sexual content, profanity, drugs, gambling or user-generated content. No in-app purchases, no ads, no account creation. The app connects to the internet for weather, scores and updates. Expected rating: Everyone / PEGI 3.

## Data safety (Google Play) / privacy answers (Amazon)

- Data collected by the developer: **None**.
- Data shared with third parties: **None** by the developer. The app calls public services directly (weather, scores, IP location for the connection pill); see the privacy policy table.
- Data encrypted in transit: Yes (HTTPS), except the optional photo receiver on the home network (local only, code-protected, runs only while open).
- Users can delete data: Yes, by uninstalling or clearing app data.

## Accessibility service declaration (Google Play requires this; Amazon may ask)

WLH Glass includes an optional accessibility service, "WLH Glass Home button".

- **Purpose:** lets people who choose WLH Glass as their home screen open it with the remote's Home button, because Google TV doesn't let users pick a different home app. It also runs the sleep timer (puts the TV to standby).
- **What it does:** listens only for the Home key (KEYCODE_HOME) and uses the "lock screen" global action for the sleep timer. For the optional screen time limit it receives TYPE_WINDOW_STATE_CHANGED events and reads only the package name of the foreground app, to add up minutes per day on the device. It doesn't read window content, text or user input; `canRetrieveWindowContent` is false and nothing leaves the device.
- **User control:** off by default. The user turns it on themselves in the TV's Accessibility settings after a clear explanation in the app, and can turn it off from WLH Glass › Settings › Home button.
- **Data:** none collected or transmitted.

Google Play only allows accessibility services that help people with disabilities, or that are core to an app's function with a prominent disclosure and consent. Expect review questions here. If Google rejects it, ship the Play version without the Home button (users open WLH Glass from Apps), and keep the full version on GitHub and Amazon.

---

## Amazon Appstore: steps

1. Create a free account at developer.amazon.com (use the Wilhoite Lux Horizon LLC details) and fill in the tax and payment interview. It's required even for free apps.
2. Apps & Services › **Add new app** › Android › name "WLH Glass".
3. **Upload your app file:** `releases/wlh-glass-latest.apk`. Device support: pick the **Fire TV** devices (Fire OS 6 or newer). Unselect phones and tablets.
4. **Appstore details:** paste the text above, then upload the icons, screenshots and Fire TV background.
5. **Content rating:** answer as above.
6. **Pricing:** Free. Availability: all countries.
7. Submit. Review usually takes a few days.

Amazon re-signs apps with its own key. An Appstore install and a Downloader install can't update each other, so a user has to uninstall one to switch. Settings › App update only updates GitHub/Downloader installs.

## Google Play: steps

1. Create a developer account at play.google.com/console ($25 one-time). Organization accounts need a D-U-N-S number, so a personal account is faster.
2. **Before uploading, two build changes are needed:**
   - Play requires an **Android App Bundle (.aab)**, not an APK.
   - Play requires **targetSdk 35** for new apps.

   Ask Claude to make the Play build. It will produce `wlh-glass-play.aab` with both changes.
3. Create app › "WLH Glass" › App, Free.
4. Under **Release › Device catalog / Advanced settings**, add **Android TV** as a form factor and upload the TV banner and TV screenshots.
5. Fill in **App content:** privacy policy URL, ads (No), data safety (as above), content rating, target audience (13+ is simplest), and the **accessibility declaration** above.
6. Use **Play App Signing** (default). Keep `glass.keystore` safe; it's your upload key.
7. Create a release on the **internal testing** track first, install it on your Google TV from the Play Store, then promote it to production.

Google re-signs Play installs too, so the same note applies: Play installs and Downloader installs can't update each other.
