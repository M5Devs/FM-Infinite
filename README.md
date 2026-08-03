# FM Infinite 🖥️
> The first FM Towns emulator for Android

[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android_7.0%2B-green.svg)](https://android.com)
[![Based On](https://img.shields.io/badge/Based_on-Tsugaru-orange.svg)](https://github.com/captainys/TOWNSEMU)

FM Infinite brings the Fujitsu FM Towns — a legendary Japanese home computer from 1989 — to your Android device. Built on top of the Tsugaru emulator core, FM Infinite aims to be the most accessible way to experience FM Towns software on mobile.

---

## ✨ Features

- 🎮 Full FM Towns & FM Towns Marty support
- 📱 Virtual gamepad with customizable layout
- 💾 Save & load states
- 📂 Smart ROM library with game covers
- 🗂️ Android 11+ scoped storage support
- 🆓 Free and open source forever

## 📋 Requirements

- Android 7.0 (API 24) or higher
- FM Towns BIOS files (not included)
- Game disc images (ISO, MDS, or CHD)

## 🚀 Getting Started

1. Install FM Infinite from the releases page
2. On first launch, select a storage folder for your files
3. Place your BIOS files in the `bios/` folder
4. Place your game images in the `roms/` folder
5. Launch a game from the library

### Required BIOS Files
```
bios/
├── TOWNS.SYS
└── TOWNSCRD.SYS
```

## 🏗️ Building from Source

### Prerequisites
- Android Studio Hedgehog or newer
- Android NDK r25c or newer
- CMake 3.22+

### Build Steps
```bash
git clone https://github.com/M5Devs/FM-Infinite.git
cd FM-Infinite
# Open android/ folder in Android Studio
# Build > Make Project
```

## 🗺️ Roadmap

- [x] Android port of Tsugaru core
- [x] Virtual gamepad
- [x] Android 11+ storage support
- [ ] CHD disc image support
- [ ] Save states UI
- [ ] Game cover scraping
- [ ] Windows/Linux desktop version
- [ ] Shader support
- [ ] Controller mapping

## 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first.

## 📄 License

This project is licensed under the BSD 3-Clause License — see [LICENSE](LICENSE) for details.

## 🙏 Credits

See [CREDITS.md](CREDITS.md) for full credits.

---

*Developed with ❤️ by [M5 Dev](https://github.com/M5Devs) 🇪🇬*
