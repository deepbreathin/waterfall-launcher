# Waterfall

A minimal Android home screen. Your apps sit behind a letter strip on the right edge, so the home
screen itself stays nearly empty.

- **Home:** a clock, the date, any widgets you add, and a short column of favourite app icons.
- **Right edge:** touch it and a letter strip appears and bends towards your finger. The apps for
  that letter show on the left, most-used first. Slide onto one and let go to open it. Let go on
  the strip instead and the full list stays where it is and scrolls freely, with search at the top.
- **Swipe left:** a side page that holds one widget of your choice (long-press it to choose).
- **Swipe down:** notifications.
- **Long-press an app** to add it to home or remove it. **Long-press empty space** for widgets and
  settings.

Letters are ordered by how often you open each app. Allow "Usage access" (long-press home, then
"Order letters by most used") and the order is right from day one.

## Install

Build the APK (see below), install it, then set Waterfall as your home app
(long-press home, then "Default home app").

## Build

    ./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk

The signing key in `app/waterfall.keystore` is public on purpose: it lets any build update any
other install in place. It is for sideloading, not for the Play Store.

## For add-ons

Waterfall broadcasts `app.waterfall.HOME_VISIBLE` with a boolean extra `visible` whenever the home
screen shows or hides, so a floating button from another app can appear only on home.

## Licence

MIT. The Nunito font is under the SIL Open Font License (`app/src/main/assets/fonts/OFL.txt`).
