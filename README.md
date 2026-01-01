# 📖 MangaAutoScroller

**Intelligent Auto-Scrolling for Manga, Manhwa & Manhua**

[![Latest Release](https://img.shields.io/github/v/release/Vincentjhon31/MangaAutoScroller?include_prereleases)](https://github.com/Vincentjhon31/MangaAutoScroller/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-6.0%2B-green.svg)](https://developer.android.com)

---

## ✨ Features

| Feature | Description |
|---------|-------------|
| 🔄 **Automatic Scrolling** | Hands-free reading experience with adjustable speed |
| 🧠 **ML Bubble Detection** | Detects speech bubbles using ONNX neural network |
| 📊 **Adaptive Speed** | Automatically adjusts scroll speed based on text density |
| 🎯 **Panel Detection** | Recognizes manga panels for smoother scrolling |
| 🔇 **Offline Mode** | Works without internet connection |
| 🔔 **Update Notifications** | In-app notifications when new version is available |
| ⚙️ **Customizable** | Adjust speed, opacity, and detection sensitivity |

---

## 📥 Download

Get the latest release from **[GitHub Releases](https://github.com/Vincentjhon31/MangaAutoScroller/releases/latest)**

---

## 📱 Requirements

- **Android Version**: 6.0 (API 23) or higher
- **RAM**: 2GB minimum (4GB recommended for ML features)
- **Storage**: ~100MB (includes ML model)

---

## 🚀 How to Use

### Installation

1. **Download** the APK from [Releases](https://github.com/Vincentjhon31/MangaAutoScroller/releases)
2. **Enable** "Install from unknown sources" in Settings
3. **Install** the APK

### Setup Permissions

The app requires these permissions to function:

| Permission | Purpose |
|------------|---------|
| **Accessibility Service** | Performs scroll gestures in other apps |
| **Display over other apps** | Shows floating control overlay |
| **Screen capture** | Analyzes page content for adaptive scrolling |

### Usage

1. **Open** your favorite manga reader app
2. **Tap** the floating MangaAutoScroller button
3. **Adjust** scroll speed using the overlay controls
4. **Enable ML detection** for intelligent text-aware scrolling
5. **Tap again** to stop scrolling

---

## 🛠️ Tech Stack

| Technology | Purpose |
|------------|---------|
| **Kotlin** | Primary language |
| **Jetpack Compose** | Modern UI toolkit |
| **ONNX Runtime** | ML model inference |
| **Google ML Kit** | OCR text recognition |
| **Retrofit** | GitHub API for update checker |
| **Material 3** | Design system |

---

## 🧠 ML Model

The app uses a trained ONNX model (`comictextdetector.pt.onnx`) to detect speech bubbles and text regions in manga pages.

- **Input**: 1024x1024 RGB image
- **Output**: Bounding boxes with confidence scores
- **Inference**: CPU-optimized (multi-threaded)

---

## 📊 Version History

| Version | Date | Highlights |
|---------|------|------------|
| v1.0.0 | Jan 2026 | Initial release with ML bubble detection |

---

## 🤝 Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

---

## 📄 License

This project is licensed under the MIT License - see [LICENSE](LICENSE) for details.

---

## 👨‍💻 Author

Made with ❤️ by [Vincentjhon31](https://github.com/Vincentjhon31)

---

## 🔗 Links

- **GitHub**: [MangaAutoScroller](https://github.com/Vincentjhon31/MangaAutoScroller)
- **Releases**: [Download APK](https://github.com/Vincentjhon31/MangaAutoScroller/releases)
- **Issues**: [Report Bug](https://github.com/Vincentjhon31/MangaAutoScroller/issues)
