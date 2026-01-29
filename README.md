# 🛡️ V-Shield Home - P-Innovation 2026

**V-Shield Home** is a mobile security application designed to protect users from modern cyber threats. Developed as part of the **P-Innovation 2026** competition, this project is built by a team of Information Security students.

---

## 🌟 Project Overview
The application utilizes a **Local VPN** mechanism to monitor and filter network traffic directly on the device. Its primary objective is to block connections to malicious, phishing, and malware-hosting domains without routing data through external servers, ensuring absolute user privacy.

## ✨ Key Features
- **Malicious Domain Blocking:** Automatically detects and prevents access to domains listed in global blocklists.
- **Local VPN Engine:** Leverages Android's `VpnService` to process packets locally with zero network latency.
- **Customizable Blocklists:** Allows users to update or integrate multiple blocklist sources.
- **Resource Optimization:** Optimized for stable background operation on Android devices without significant battery drain.

## 💻 Technical Stack
- **Language:** Java (Android SDK).
- **IDE:** Android Studio.
- **Version Control:** Git & GitHub.
- **Core Architecture:** Local VPN Service (VpnService API).

## 📂 Project Structure
- `app/src/main/java/`: Core source code for VPN logic and system administration.
- `app/src/main/assets/`: Contains `blocklist_sample.txt` defining the blocked domains.
- `app/src/main/res/`: User interface assets and application resources.

## 🚀 Installation & Setup
To run this project on your local machine, follow these steps:

1. **Clone the project:**
   ```bash
   git clone [https://github.com/quangchi306/vshield-pinnovation.git](https://github.com/quangchi306/vshield-pinnovation.git)
