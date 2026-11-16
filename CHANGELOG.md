# Changelog

## 1.3.0 - Camera feature upgrade
- Added front/rear camera switching, including segmented recording that is merged into one final video.
- Added photo flash modes and rear-camera torch control for video.
- Added optional GPS EXIF tagging for photos when location permission is granted.
- Added latest LoFiCam gallery shortcut.
- Added Retro, Warm, B&W, Cool, and Original photo looks.
- Added hardware-limited zoom control and native-resolution HD mode.
- Kept MP4/3GP export and low-resolution video output.

## 1.1.0 - 2026-11-16

### Added
- ARM64-only APK packaging and explicit app version metadata.
- Video resolution and frame-rate target controls through CameraX.
- GitHub Actions workflow for tagged APK releases.
- Documentation of compatibility, current capabilities, and known limitations.

### Fixed
- Photo capture now saves a JPEG file before processing, avoiding attempts to decode YUV camera planes as JPEG.
- Photo processing keeps the intended low-resolution output instead of allocating several full-sensor-size bitmaps.
- Photo gallery writes now report errors and remove incomplete MediaStore entries.
- 3GP output is validated after muxer finalization.

### Known limitations
- Camera hardware may choose a nearby supported video size or frame rate rather than the exact slider value.
- Video is remuxed into 3GP; it does not yet receive the photo grain, vignette, or color-grade effects.
- CI APK is debug-key signed for sideload testing and is not a Play Store production build.
- A successful CI build is not a substitute for physical-device testing.

## 1.2.0 - 2026-11-16

### Added
- Choose MP4 or 3GP as the saved video container.
- Retro minimal camera controls with warm paper, sage, and burnt-orange colors.
- Video output scaling after capture using Media3.

### Notes
- 1p-15p selections map to a 16-pixel encoder-safe minimum; actual output is subject to encoder constraints.
- APK size is optimized with R8 and resource shrinking. The build is not padded to an arbitrary 20 MB target.

- Added pixel-linked crunchy audio processing for low-resolution video exports (build validation pending).
