# 📺 Sambhav TV OS — Best Ad-Free Custom Android TV UI Launcher

[![GitHub release](https://shields.io)](https://github.com)
[![Platform](https://shields.io)](#)
[![License](https://shields.io)](#)

Looking for the **best TV UI in the world** without the corporate clutter? **Sambhav TV OS** is a premium, open-source, and completely ad-free Android TV launcher built from the ground up to rescue your Smart TV from sluggish stock firmware and aggressive sponsored ads.

Inspired by the clean, cinematic aesthetic of premium streaming hardware, this native launcher replaces busy layouts with a distraction-free, privacy-first interface optimized for TCL and generic Android 9+ televisions.

---

## ✨ Key Features

*   **Cinematic Dark Theme:** Features an elegant, minimal home screen layout with a large prominent visual panel.
*   **Fully Customizable Wallpapers:** Easily personalize your television with custom background image support.
*   **Zero Ads & Tracking:** No third-party analytics, no mandatory account sign-ups, and absolutely zero network data mining permissions.
*   **Native Performance:** Written completely in native Kotlin Views for smooth, lightning-fast rendering even on low-RAM legacy TV hardware.
*   **Smart App Discovery:** Automatically scans, indexes, and cleanly populates your local Android TV applications and regional streaming platforms (like Netflix, Prime Video, JioHotstar, and Spotify) into uniform cards.
*   **D-Pad Optimization:** Designed perfectly for standard infrared and Bluetooth TV remotes with fluid focus handling and immediate OK/Enter D-pad execution.
*   **Real-Time Dashboard Widget:** Displays a beautifully stylized dynamic clock and real-time calendar header.

---

## 💾 Installation Guide

Getting a clean, premium TV layout takes less than five minutes:

### Step 1: Grab the Installer APK
1. Head over to the [Sambhav TV OS Releases Page](https://github.com).
2. Download the latest pre-compiled `Sambhav-TV-OS-debug.apk` file asset.

### Step 2: Sideload onto your Smart TV
1. Copy the downloaded APK file to a USB flash drive and plug it into your TV's USB port (or transfer it wirelessly using a local file utility like *Send Files to TV*).
2. Open your TV's built-in File Manager application.
3. Click on the APK file to install it. *(Note: If prompted, allow your File Manager permission to install apps from "Unknown Sources" under your TV's Security Settings).*

### Step 3: Launch and Set as Default
1. Launch **Sambhav TV OS** directly from your app drawer.
2. Press the **Home** button on your remote control.
3. When the Android firmware displays the system launcher chooser dialog, select **Sambhav TV OS** and click **Always** to establish it as your permanent interface.

---

## 🛠️ Developer & TCL Compatibility Notes

*   **Application ID:** `in.sambhav.tvos`
*   **Target Level:** Android 9 (API 28) up to recent versions.
*   **Build Environment:** Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17.

> [!NOTE]
> Some proprietary TCL firmware variants aggressively lock down the remote's home button layout. If your display blocks you from assigning the launcher permanently via the on-screen UI, you can easily bypass this by enabling **USB Debugging** in your TV's Developer Settings and disabling the stock home layout using the following network ADB shell command:
> `adb shell pm disable-user --user 0 com.tcl.home`

---

## 🤝 Support the Project

If you love having a clean, ad-free, high-performance television screen, consider supporting the hard work behind this layout:
*   ⭐ **Star this repository** to help push it up on GitHub and Google search trends!
*   🔀 **Fork the repository** to contribute features, localization packages, or custom UI tweaks.
*   💬 **Share your setup** with the community on subreddits like `r/AndroidTV` or tech forums.

*Disclaimer: This is an original independent launcher design. It is not affiliated with Apple Inc. or TCL, and does not host or distribute copyrighted streaming media channels.*
