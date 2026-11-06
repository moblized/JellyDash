# JellyDash

Watch your Jellyfin movies and shows on your car's Android Auto screen while you're parked. An Android app built with Kotlin, the Android for Cars App Library and Media3.

## What it does

- **Browse on the car screen.** Continue watching, Next up, and your video libraries. Big libraries split into A–Z groups with a "Recently added" shelf. Shows open to their next episode and seasons. Search works when you're parked (Android Auto only shows the keyboard then).
- **Play from your server.** Files the phone can handle play as they are. Anything else, your server converts: HDR is tone-mapped (a car screen is SDR), and surround sound comes over as stereo AAC. Pick audio tracks and subtitles from the player. Picture subtitles (PGS, DVD) are drawn into the video by the server.
- **Stays in sync.** Resume points, "watched" and the "Now playing" view on your Jellyfin dashboard update as you watch. Next episode is a tap away when one ends.
- **Parked only.** The picture turns off as soon as the truck moves. Depending on your setting, the sound keeps going or it pauses. To bring the picture back, stop for 20 seconds and tap **I'm parked**. It never comes back on its own, because a phone can't tell a long red light from parking.
- **Phone app** for signing in, the streaming quality (3, 8 or 20 Mbps, or original), what happens when you drive off, light or dark mode (the launcher icon switches too) and updates.

## How the parked check works

JellyDash reads the truck's speed when Android Auto shares it, and the phone's GPS speed. One clear reading above about 11 mph, or two in a row above about 6 mph, counts as driving. GPS readings that aren't accurate enough are skipped, so drift in a parking lot doesn't count. If no speed reading has come in for 15 seconds, the player says so.

Allow location for JellyDash on your phone so the check works. Without it the app can't see you moving.

## Setup

1. Install the APK from [Releases](../../releases) on your phone (allow "Install unknown apps" for your browser the first time). After that the app offers updates itself.
2. Open JellyDash on the phone, sign in to your server (use the address that works away from home), and allow location.
3. Turn on unknown sources in Android Auto: **Settings › Connected devices › Android Auto**, tap **Version** 10 times to turn on developer settings, then **⋮ › Developer settings › Unknown sources**.
4. In the truck, open the app list on the screen and pick JellyDash. It's listed with the navigation apps.

## Why it's a "navigation app"

Android Auto only gives a full-screen drawing surface to navigation apps. JellyDash uses that surface for the video: it creates a private display on it and shows an ordinary video player there. That also means Android Auto treats it like one navigation app among several, so while JellyDash is open, switch back to Google Maps for directions.

Google is rolling out official parked video for some car brands. This app is for cars that don't have it yet.

## Privacy

Your server address and sign-in stay on the phone and are left out of Google backups. The app only talks to your Jellyfin server and to this repo's Releases to check for updates. Speed and location are used on the phone for the parked check and never sent anywhere.

## How the build works

`.github/workflows/build.yml` runs the unit tests (including every Android Auto screen built against a pretend server, which checks Android Auto's template rules), renders the phone screens to PNGs with Roborazzi (published to the `screenshots` prerelease), builds and signs the APK, and attaches it to a GitHub release. Every build uses the same signing key in `keystore/`, so updates install over the previous version.

To build locally you need JDK 17 and the Android SDK:

```sh
./gradlew testDebugUnitTest assembleRelease
```
