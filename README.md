# ARKAN CAD PRO V1.0

Android-first, offline-capable CAD project starter for sewer and infrastructure workflows.

## Current repository contents

- Android app starter using Kotlin and Jetpack Compose.
- Dark CAD-style touch sketch surface.
- File picker for selecting `.lsp` files. **Selecting an LSP file on Android does not execute AutoLISP.**
- KML export of a WGS84 reference point (longitude/latitude).
- GitHub Actions workflow to build a debug APK in the cloud and upload it as an artifact.
- Desktop AutoCAD sample AutoLISP utility: `lisp/ARKAN_SEWER_TOOLS.lsp`.

## Build APK in GitHub

1. Open the repository's **Actions** tab.
2. Select **ARKAN Android APK**.
3. Press **Run workflow** (or wait for a build after a push).
4. Open the completed workflow run.
5. Download the artifact named **ARKAN-CAD-PRO-debug-apk**.
6. Extract the ZIP and install `app-debug.apk` on an Android device. Android may ask you to allow installation from that source.

## AutoLISP and AutoCAD compatibility

The LSP file in `lisp/` is intended for **desktop AutoCAD**. In AutoCAD, use `APPLOAD` and select `ARKAN_SEWER_TOOLS.lsp`, then run `ARKANINFO` or `ARKANLEN`.

A native AutoLISP interpreter compatible with legacy AutoCAD LISP files is **not included in this Android starter**. Android file selection is not LISP execution. Full compatibility requires a separate interpreter/runtime and compatibility testing against real DWG drawings.

## KML scope in this starter

The current app exports one WGS84 reference point as KML. Exporting CAD entities (POINT/LINE/POLYLINE/POLYGON, manholes, roads and buildings), plus UTM zone/hemisphere to WGS84 conversion, is planned work and is not yet implemented. Do not treat screen pixels as geographic coordinates.

## Planned engineering modules

1. CAD entities, layers, blocks, selection and command line.
2. Sewer/manhole tools and quantity takeoff.
3. Manhole schedule with N/E coordinates, depth classes AS/BS/CD, and rectangular/circular dimensions.
4. Length totals grouped by drawing layer; excavation and backfill quantities.
5. Excel/PDF/DXF exports and DWG interoperability.
6. Full KML import/export and UTM/WGS84 transformations.
7. AutoLISP runtime evaluation and compatibility suite (desktop integration first).

## Project requirements

- Android first; Arabic/English interface planned.
- Offline operation for core drafting and quantity workflows.
- App ID: `com.arkan.cadpro`.
- Version: `1.0` (starter).
