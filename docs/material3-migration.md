# Material Design 3

ArcaenBox uses the Material 3 Android Views theme and Material Components 1.13.0.
The existing activities, navigation destinations, proxy editors, database, and
preference keys are retained.

## Appearance

- The brand color `#DCEEF1` is the light primary container. Darker teal text and
  action colors keep controls readable. Light and dark palettes explicitly cover
  surfaces, containers, outlines, error colors, and their foreground colors.
- Toolbars, navigation drawer, cards, tabs, floating action button, buttons,
  checkboxes, chips, input fields, and dialogs use Material 3 styles.
- Settings switches use `MaterialSwitch` while retaining `SwitchPreference`
  persistence, dependency handling, and change listeners. Single-choice settings
  use Material dialogs and retain their stored values.
- Content respects system bars, display cutouts, and the keyboard. Backup and
  debug controls scroll on short screens. Existing alternate color choices remain
  available.
- The supplied illustration, `com.arcaenbox.android` application ID, and dedicated
  ArcaenBox release signing key are unchanged.

The implementation follows the official [Material Components getting-started
guide](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md).

## Validation

The signed APK workflow verifies all four architecture APKs, their signatures,
compiled names, package IDs, providers, shortcuts, and embedded illustration.
`.github/scripts/ui_smoke.py` installs the x86_64 release on an isolated Android 15
emulator and exercises navigation, switch persistence, a local SOCKS profile import
and editor, single-choice and text dialogs, and backup controls. It captures
screenshots and UI hierarchies in light/dark mode, at increased font scale, and
with compact and landscape screen dimensions. Evidence is uploaded as
`ArcaenBox-UI-verification`.

The emulator script does not connect to a real proxy or test VPN traffic. Passing
it does not certify every Android version or device. The nine main text/background
color pairs have a minimum contrast of 6.23:1 in light mode and 5.47:1 in dark mode.
