# V-Shield Home 🛡️

![Platform](https://img.shields.io/badge/Platform-Android-green)
![Status](https://img.shields.io/badge/Status-Prototype-orange)
![Tech](https://img.shields.io/badge/Core-VpnService%20%2F%20TFLite-blue)

**V-Shield Home** is an Android-based network security application designed to protect home users from online threats. It operates as a local network filter to block phishing, malware, gambling, and inappropriate content directly on the device.

This project is developed for the **P-Innovation 2026** competition (PTIT HCM).

## Overview

Unlike traditional VPNs that route traffic through external servers, V-Shield Home uses the Android **VpnService** to inspect DNS packets locally. This ensures user privacy (data never leaves the device) and minimizes network latency.

The system employs a **Hybrid Detection Engine** combining static blocklists with an on-device Machine Learning model to detect zero-day phishing attacks.

## Key Features

* **Local Traffic Filtering:** Blocks domain resolution for known malicious hosts without root access.
* **Real-time URL Analysis:** Integrated **TensorFlow Lite** model to classify unknown URLs based on lexical features (entropy, length, suspicious keywords) in real-time.
* **Category Blocking:**
    * Phishing & Scam
    * Malware & Spyware
    * Gambling services
    * Adult content
* **Privacy First:** No data tracking, no remote logging. Everything runs offline.
* **Battery Efficient:** Optimized for background execution with minimal resource usage.

## Architecture & Innovation

We address the limitation of static blacklists (which are often slow to update) by implementing a two-layer filter:

1.  **Layer 1 (Speed):** Checks DNS requests against a local SQLite database (sourced from *Chongluadao*, *PhishTank*).
2.  **Layer 2 (Intelligence):** If a domain is unknown, the **Edge AI module** analyzes the URL string structure.
    * *Technology:* Custom CNN/LSTM model optimized for mobile (TinyML).
    * *Benefit:* Can block generated phishing domains (e.g., `secure-login-bank-v8.xyz`) that haven't appeared in blacklists yet.

## Tech Stack

* **Language:** Kotlin
* **Core API:** Android VpnService, PCAP (Packet Capture)
* **ML Engine:** TensorFlow Lite
* **Database:** Room (SQLite)
* **Architecture:** MVVM, Coroutines


## Getting Started

### Prerequisites
* Android Studio Ladybug (or newer).
* Android device running SDK 26 (Oreo) or higher.

### Installation
1.  Clone the repository:
    ```bash
    git clone [https://github.com/quangchi306/vshield-pinnovation.git](https://github.com/quangchi306/vshield-pinnovation.git)
    ```
2.  Open the project in Android Studio.
3.  Sync Gradle dependencies.
4.  Connect a physical Android device (recommended for VPN testing).
5.  Build and Run.

## 🤝 Contributors

**Team V-Shield - PTIT HCM**

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
