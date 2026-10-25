# Clean Browser for Android

A simple Android browser that blocks ads, trackers, pop-ups, pop-unders and ad redirects.

## What it blocks
- Ads & trackers (StevenBlack + AdAway block lists, ~90,000 domains, refreshed every 3 days)
- Pop-ups and pop-unders — websites can never open new windows
- Forced redirects to other sites that you didn't tap on
- `intent://` / Play Store jumps that throw you into other apps
- Invisible full-screen "tap anywhere" ad layers
- Camera, mic and location requests

Tap the 🛡 counter to see how many things were blocked. If it shows `!`, a redirect was blocked —
tap it to open that page anyway if you really wanted it.

Downloads always ask before starting.

## Getting the APK
GitHub builds the app automatically (see `.github/workflows/build.yml`).
After each upload, open the repo's **Releases** page and download `CleanBrowser.apk`.
