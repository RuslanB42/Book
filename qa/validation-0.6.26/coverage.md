# 0.6.26 coverage

- Closing patterned raster cover: bottom92% visible at0/70/160/260/390/459/460ms for carousel and grid; same artwork pixel plus mean interior RGB difference <2 before/after handoff; source hidden during flight and restored at removal.
- No visible 'О книге' TextView; close/menu controls and optical top fade retained.
- Shared cover explicit dispatchDraw with manual transform; no bounded saveLayer; explicit invalidate per pose re-records parent display list on hardware. Native software tests cannot reproduce/validate vendor GPU RenderNode behavior.
- Original FlameView with real streak count, active/risk state and number; large-font layout retained.
- Dark adaptive launcher, monochrome glyph, safe-circle and optical centering regressions. Boot loading view has ImageView, not TextView; old Android starting-window drawable rendered too.
- Full unit suite, assembleDebug and lintDebug; certificate/appId/version checks in packaging.

Review one rendered image at a time; log each PASS/FAIL before opening another. No physical device or FPS measurement.
