# Kuwago — SMS Smishing & Phishing Protection App

Kuwago is an Android application designed to protect users against SMS smishing (phishing via SMS), unsolicited spam, and malicious web links. It combines fast on-device machine learning with server-side deep learning (CNN-BiGRU) and real-time online URL threat intelligence.

---

## 🎯 Classification Labels & Thresholds

SMS messages are classified into three human-readable risk levels based on the calculated ensemble probability score (`finalProb` between `0.0` and `1.0`):

| Classification Label | Final Score Range | Description & Status Badge |
| :--- | :--- | :--- |
| **Harmful** | $\ge 85\%$ (`0.85`) | 🚨 High risk SMS scam / credential phishing. |
| **Suspicious** | $70\% \text{ to } 84\%$ (`0.70 - 0.84`) | ⚠️ Unsolicited, promotional, or high-urgency content. |
| **Safe** | $< 70\%$ (`0.70`) | ✅ No threat or scam patterns detected. |

*Note: Percentage scores are de-emphasized to the technical details modal and removed from push notifications to reduce user confusion.*

---

## 🧮 Ensemble Decision Calculation Logic

The final ensemble decision probability is computed dynamically depending on whether a web link is present and whether online scans (CNN Deep Learning & URL Reputation) have completed:

### 1. Message Contains NO Web Link
* **Local Pre-Scan / Offline Mode**:
  $$\text{finalProb} = 100\% \text{ Local ML} \quad (75\% \text{ Random Forest} + 25\% \text{ XGBoost})$$
* **Deep Scan Completed (CNN-BiGRU API)**:
  $$\text{finalProb} = (66.7\% \times \text{CNN Score}) + (33.3\% \times \text{Local ML Score})$$
  *(Preserves the 2:1 ratio of Deep Learning to Local ML without diluting the score).*

---

### 2. Message Contains a Web Link, but Online URL Scan is PENDING / UNAVAILABLE
*(e.g., local pre-scan, offline mode, or pending VirusTotal threat intelligence — `url.score == null`)*

* **Local Pre-Scan Only**:
  $$\text{finalProb} = 100\% \text{ Local ML}$$
* **Deep Scan (CNN) Completed, but URL Scan Pending/Skipped**:
  $$\text{finalProb} = (66.7\% \times \text{CNN Score}) + (33.3\% \times \text{Local ML Score})$$
  *(Pending URLs are not assigned a 0.0 score; the ensemble decision relies 100% on active text models until the URL scan finishes).*
* **UI Status**: Displays `Pending` for the URL layer in accordion summaries, `0% • Pending Scan` in the breakdown list, and appends a URL caution note to the human-readable explanation.

---

### 3. Message Contains a Web Link, and Online URL Scan HAS COMPLETED

* **Full 3-Layer Ensemble** *(CNN + URL Scan + Local ML)*:
  $$\text{finalProb} = (50\% \times \text{CNN Score}) + (25\% \times \text{URL Score}) + (25\% \times \text{Local ML Score})$$
* **URL Scan + Local ML** *(If CNN API server is unavailable)*:
  $$\text{finalProb} = (50\% \times \text{URL Score}) + (50\% \times \text{Local ML Score})$$
* **Local URL Cache Hit** *(Known host in local blocklist/allowlist)*:
  $$\text{finalProb} = (75\% \times \text{Local ML Score}) + (25\% \times \text{Cached URL Score})$$

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

## ⚙️ Development & Configuration

### API Endpoint Configuration
Set your backend API endpoint in your development environment's `local.properties` file:
```properties
SMISHING_API_BASE_URL=http://your-backend-api-url.com/
```

### Running Unit Tests
Execute unit tests from the project root:
```bash
./gradlew test --no-build-cache
```
