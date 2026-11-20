# ARKAN CAD PRO V1.11

Android-first, offline-capable CAD and sewer-network field tool.

## Available in the current application

- Arabic CAD-style touch workspace with manual pipe and manhole placement.
- Pipe diameter presets: 250, 315, 400, 500, 600 and 700 mm, with layer-based colors.
- Manhole entry with GL and INV, automatic depth/type classification, and slope-based downstream INV calculation when pipe endpoints are snapped near manholes.
- Manhole schedule and quantity summaries, including pipe lengths by layer and estimated trench/manhole excavation.
- CSV, XLSX and PDF quantity reports.
- DXF import for supported entities: LINE, LWPOLYLINE, POLYLINE, CIRCLE, ARC, ELLIPSE and SPLINE. INSERT manhole recognition depends on block/layer naming; arbitrary nested blocks are not fully expanded.
- DWG import/export through bundled LibreDWG command-line converters. Real-world DWG compatibility still requires testing against the user's drawings.
- DXF and KML export. KML conversion assumes coordinates are UTM in the selected northern-hemisphere zone; default zone is 38N for Iraq. Select the correct zone and provide a valid UTM reference for manually drafted geometry.
- Desktop AutoCAD sample AutoLISP utility: `lisp/ARKAN_SEWER_TOOLS.lsp`.

## Important coordinate limitations

- CAD drawings can use local coordinates, arbitrary project grids, or georeferenced UTM coordinates. The app cannot infer an unknown coordinate reference system reliably.
- For correct KML placement, the drawing must contain valid UTM coordinates in the selected zone, or manual geometry must be tied to a known UTM reference point.
- Do not interpret screen pixels or arbitrary local drawing coordinates as geographic coordinates.
- UTM zone selection controls KML longitude conversion. The current workflow targets northern-hemisphere projects.

## AutoLISP compatibility

Selecting an LSP file on Android does not execute AutoLISP. The sample LSP is intended for desktop AutoCAD: use `APPLOAD`, then run `ARKANINFO` or `ARKANLEN`. A native AutoLISP interpreter compatible with arbitrary AutoCAD LISP files is not included.

## Build APK

1. Open the repository's **Actions** tab.
2. Select **ARKAN Android APK**.
3. Open the latest successful run.
4. Download the artifact named **ARKAN-CAD-PRO-stable-signed-apk**.
5. Extract the ZIP and install the signed APK on Android.

## Remaining validation work

- Test DWG import with representative customer drawings, including blocks, nested inserts, proxy objects, fonts and units.
- Add explicit local-grid/georeferencing workflows before generating KML from non-UTM drawings.
- Expand block expansion and support additional DXF entity types after reviewing real sample files.
- Verify quantities and manhole labels against surveyed project data.
- A successful APK build confirms packaging and compilation, not correctness for every DWG drawing.
