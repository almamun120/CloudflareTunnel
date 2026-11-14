# Cloudflare (com.cloudflare.tunnel)

Quick + **Named** টানেল, credentials ইমপোর্ট, **Backup/Restore**, এবং টানেলের URL **Firebase Realtime DB**-তে সিঙ্ক — সব ফিচার আগের মতোই আছে।

## এই ভার্সনে ঠিক/নতুন
- **নতুন ডার্ক UI** (Cloudflare-অরেঞ্জ): বাইনারি কার্ড, টানেল কার্ড (QUICK/NAMED ব্যাজ, LIVE/CONNECTING), কনসোল, "New tunnel" বাটন; Add/Edit একই আধুনিক ডায়ালগ।
- **Named tunnel**: কানেকশন রেজিস্টার হলে তবেই LIVE; URL এখন সবসময় `https://` সহ (কপি/QR ঠিক); tunnel name ফাঁকা থাকলে ID ব্যবহার; Firebase এখন Named-এও চলে।
- **Backup/Restore**: এখন `.cloudflared` ফোল্ডারের সাথে **সব টানেল কনফিগও** (`tunnels.json`) ব্যাকআপ হয়, রিস্টোরে মার্জ হয়। রিস্টোরে **Zip-Slip** (../ পাথ) ও zip-bomb প্রতিরোধ। Firebase key ব্যাকআপে রাখা ঐচ্ছিক।
- **Firebase**: `PATCH` Android-এ নির্ভরযোগ্য নয় → `X-HTTP-Method-Override`; auth key URL-encode।
- **সিকিউরিটি**: tunnel name/hostname/credentials ফাইলে শেল-ইনজেকশন আটকানো (শুধু নিরাপদ অক্ষর); ইউজার-CA ট্রাস্ট বাদ; অপ্রয়োজনীয় লোকেশন/ফোন পারমিশন ও VPN সার্ভিস বাদ।
- **CI কম্পাইল এরর** (`Process.pid()` unresolved) ঠিক।
- টানেল বন্ধ করা নন-ব্লকিং; `TunnelManager` অ্যাপ-স্কোপ (নোটিফিকেশনের "Stop All" কাজ করে); TAR/XZ আংশিক-রিড বাগ, ভার্সন রেজেক্স, CPU আর্কিটেকচার-ভিত্তিক ডাউনলোড; লগ ১০০০ লাইনে সীমিত; `SSL_CERT_FILE` ভুল সরানো; https অরিজিনে `--no-tls-verify`; নেটিভ `fork()` কোড সরানো।
- Gradle: Gradle 8.10.2 ↔ AGP 8.7.3, Compose বাদ।

> `targetSdk=28` ইচ্ছাকৃত — Android 10+ এ বেশি হলে অ্যাপের স্টোরেজ থেকে বাইনারি চালানো যায় না। এটা সাইডলোডের জন্য।

## GitHub Actions (অটো বিল্ড)

`.github/workflows/android.yml` যা করে:

| কখন | কী হয় |
|---|---|
| `main`/`master`-এ push, Pull Request, বা Actions → *Run workflow* | **Debug APK** + **Signed Release APK** দুটোই বিল্ড হয়, Artifacts-এ পাওয়া যায় |
| `v1.2.0` ধরনের ট্যাগ push | উপরের দুটো APK সহ **GitHub Release** তৈরি হয় |

### নিজের কী দিয়ে সাইন করতে (ঐচ্ছিক, কিন্তু আপডেটের জন্য জরুরি)
```bash
keytool -genkeypair -v -keystore release.jks -alias myalias -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks     # আউটপুটটা KEYSTORE_BASE64 সিক্রেটে বসান
```
Repo → Settings → Secrets and variables → Actions → এই ৪টা যোগ করুন:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

সিক্রেট না দিলে workflow প্রতি রানে একটা অস্থায়ী কী বানিয়ে সাইন করে (APK ইনস্টল হবে, তবে আলাদা কী-র APK-এর ওপর আপডেট হবে না)।

ভার্সন বদলাতে `gradle.properties`-এর `APP_VERSION_NAME` এডিট করুন; `versionCode` = CI run number।
