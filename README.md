# Kuwago — SMS Smishing & Phishing Protection App

Kuwago is an Android application designed to protect users against SMS smishing (phishing via SMS), unsolicited spam, and malicious web links. It combines fast on-device machine learning with server-side deep learning (CNN-BiGRU) and real-time online URL threat intelligence.

---

## 🎯 Classification Labels & Thresholds

SMS messages are classified into three human-readable risk levels based on the calculated probability score (`finalProb` between `0.0` and `1.0`). Thresholds adapt dynamically to the active scan mode:

| Scan Case | Model Weights | Safe | Suspicious | Harmful |
| :--- | :--- | :---: | :---: | :---: |
| **Offline / Local ML Only** | 50% RF + 50% XGB | `< 40%` (`< 0.40`) | `40% – 69.9%` (`0.40 – 0.699`) | `≥ 70%` (`≥ 0.70`) |
| **Online (Text with URL)** | 20% DL + 60% ML + 20% URL | `< 50%` (`< 0.50`) | `50% – 74.9%` (`0.50 – 0.749`) | `≥ 75%` (`≥ 0.75`) |
| **Online (Text-Only, No URL)** | 33.3% DL + 66.7% ML | `< 50%` (`< 0.50`) | `50% – 74.9%` (`0.50 – 0.749`) | `≥ 75%` (`≥ 0.75`) |

*Note: Percentage scores are de-emphasized to the technical details modal and removed from push notifications to reduce user confusion.*

---

## 🧮 Ensemble Decision Calculation Logic

The final ensemble decision probability is computed dynamically depending on whether a web link is present and whether online scans (CNN Deep Learning & URL Reputation) have completed:

### 1. Message Contains NO Web Link
* **Local Pre-Scan / Offline Mode**:
  $$\text{finalProb} = 100\% \text{ Local ML} \quad (50\% \text{ Random Forest} + 50\% \text{ XGBoost})$$
  *(Thresholds: `0.40` Suspicious, `0.70` Harmful)*
* **Deep Scan Completed (CNN-BiGRU API)**:
  $$\text{finalProb} = (33.3\% \times \text{DL Score}) + (66.7\% \times \text{Local ML Score})$$
  *(Thresholds: `0.50` Suspicious, `0.75` Harmful)*

---

### 2. Message Contains a Web Link, but Online URL Scan is PENDING / UNAVAILABLE
*(e.g., local pre-scan, offline mode, or pending URL threat intelligence — `url.score == null`)*

* **Local Pre-Scan Only**:
  $$\text{finalProb} = 100\% \text{ Local ML} \quad (50\% \text{ RF} + 50\% \text{ XGB})$$
* **Deep Scan (CNN) Completed, but URL Scan Pending/Skipped**:
  $$\text{finalProb} = (33.3\% \times \text{DL Score}) + (66.7\% \times \text{Local ML Score})$$
  *(Pending URLs are not assigned a 0.0 score; the ensemble decision relies 100% on active text models until the URL scan finishes).*
* **UI Status**: Displays `Pending` for the URL layer in accordion summaries, `0% • Pending Scan` in the breakdown list, and appends a URL caution note to the human-readable explanation.

---

### 3. Message Contains a Web Link, and Online URL Scan HAS COMPLETED

* **Full 3-Layer Ensemble** *(DL + Local ML + URL Scan)*:
  $$\text{finalProb} = (20\% \times \text{DL Score}) + (60\% \times \text{Local ML Score}) + (20\% \times \text{URL Score})$$
  *(Thresholds: `0.50` Suspicious, `0.75` Harmful)*
* **URL Scan + Local ML / Local URL Cache Hit** *(If CNN API server is unavailable or served from cache)*:
  $$\text{finalProb} = (50\% \times \text{URL Score}) + (50\% \times \text{Local ML Score})$$

---

## 📝 Human-Readable Explanations

Each scanned message generates a human-readable explanation detailing the reasoning behind its classification based on detected triggers:

- **Urgency Triggers**: High-pressure urgency phrasing or prize/reward claims.
- **Financial Triggers**: References to financial institutions or e-wallet services (BDO, BPI, GCash, Maya, etc.).
- **Telecom Triggers**: Telecom carrier promo or account references (Smart, Globe, DITO, etc.).
- **Action Prompts**: Call-to-action keywords (e.g., *click, verify, claim, log in*).
- **Unverified Link Warning**: When a URL is present but online threat intelligence scan results are pending/unavailable, the explanation automatically appends:
  > *"Exercise caution: this message contains a web link ($url) that has not been verified by online threat intelligence yet, so its safety cannot be guaranteed."*

---

## 🛡️ Automated Security Auditing (MobSF CI/CD Pipeline)

Every push to `main` or `test_mobsf` triggers the automated Android CI/CD security pipeline defined in [`.github/workflows/release.yml`](.github/workflows/release.yml), which audits the compiled APK using **Mobile Security Framework (MobSF)**.

### How the MobSF Pipeline Works
1. **Official Self-Hosted Engine**: The GitHub Actions runner pulls and launches the official OpenSecurity Docker container (`opensecurity/mobile-security-framework-mobsf:latest`) in the background on `localhost:8000`.
2. **Release-Signed Build & Unit Testing**:
   - Generates a PKCS12 release signing keystore (`keytool`) so the APK is signed with a production-grade certificate (`CN=Kuwago Security`) rather than Android's default debug key.
   - Runs the JUnit test suite (`./gradlew test`) and compiles the signed APK.
3. **Automated Static Analysis (SAST)**:
   - Uploads the compiled APK to the local MobSF REST API (`/api/v1/upload`).
   - Executes a full static security scan (`/api/v1/scan`) inspecting `AndroidManifest.xml`, Network Security Configuration, signing certificates, decompiled DEX bytecode, and native `.so` libraries.
   - Exports the official PDF (`mobsf-report.pdf`) and JSON (`mobsf-report.json`) reports via `/api/v1/download_pdf` and `/api/v1/report_json`.
4. **Privacy & Artifact Storage**:
   - **No External Accounts or Cloud Tracking**: MobSF runs completely self-hosted inside an isolated, ephemeral Docker container on the GitHub Actions runner. No data or APK files are ever sent to third-party MobSF servers, and the container is destroyed immediately after the job completes.
   - **Repository-Only Access**: The generated `mobsf-report.pdf`, `mobsf-report.json`, and signed APK are attached directly to the **GitHub Actions Workflow Artifacts** and **GitHub Releases** of this repository.

---

## ⚙️ Development & Configuration

### API Endpoint Configuration
Set your backend API endpoint and credential in your development environment's `local.properties` file:
```properties
API_URL=https://your-backend-api-url.com/
KWAGO_API_KEY=your-api-credential
```

### Running Unit Tests
Execute unit tests from the project root:
```bash
./gradlew test --no-build-cache
```
