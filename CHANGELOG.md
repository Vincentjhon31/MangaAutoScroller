# Changelog

All notable changes to MangaAutoScroller will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-01-01

### Added
- 🔄 **Automatic Scrolling** - Hands-free manga reading with adjustable speed
- 🧠 **ML Bubble Detection** - ONNX-powered speech bubble detection
- 📊 **Adaptive Scrolling** - Adjusts speed based on text density
- 🎯 **Panel Detection** - Recognizes manga panels for smoother scrolling
- 🎨 **Material 3 UI** - Modern design with floating overlay controls
- ⚙️ **Settings** - Customize speed, opacity, and detection sensitivity
- 📲 **In-App Updates** - Automatic update notifications from GitHub releases
- 🔇 **Offline Mode** - Works without internet (except update checks)

### Technical
- Kotlin + Jetpack Compose
- ONNX Runtime 1.16.3 for ML inference
- Google ML Kit for OCR
- Retrofit for GitHub API (update checker)
- Target SDK 36, Min SDK 23

---

## Version Format

- **MAJOR.MINOR.PATCH** (e.g., 1.0.0)
- **MAJOR**: Breaking changes or major redesigns
- **MINOR**: New features (backward-compatible)
- **PATCH**: Bug fixes and small improvements

---

## Links

- [Releases](https://github.com/Vincentjhon31/MangaAutoScroller/releases)
- [Issues](https://github.com/Vincentjhon31/MangaAutoScroller/issues)
