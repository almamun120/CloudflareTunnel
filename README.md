# Cloudflare Tunnel (Android)

## নতুন/ঠিক করা
- **আধুনিক ডার্ক UI** (Cloudflare-অরেঞ্জ): বাইনারি স্ট্যাটাস কার্ড, লাইভ টানেল কার্ড, কনসোল, "New tunnel" FAB।
- লগ ডুপ্লিকেট দেখানোর বাগ (দুটো কলব্যাক) ঠিক; লগ ১০০০ লাইনে সীমাবদ্ধ (মেমোরি লিক)।
- টানেল বন্ধ করা এখন মেইন থ্রেড ব্লক করে না (আগে UI জমত/ANR ঝুঁকি)।
- `TunnelManager` এখন অ্যাপ-স্কোপ singleton — নোটিফিকেশনের "Stop All" সত্যিই টানেল বন্ধ করে।
- cloudflared ডাউনলোড: TAR/XZ আংশিক-রিড বাগ (`not found in archive`), ভার্সন রেজেক্স, **ডিভাইসের CPU অনুযায়ী** (arm64/arm/x86_64) প্যাকেজ।
- `SSL_CERT_FILE` ভুল (ডিরেক্টরি) সরানো; https অরিজিনে `--no-tls-verify`; ব্যাকগ্রাউন্ড `startService` ক্র্যাশ গার্ড।
- নেটিভ C++ থেকে বিপজ্জনক `fork()` কোড সরানো (কখনো ব্যবহৃত হতো না); অপ্রয়োজনীয় পারমিশন (লোকেশন, ফোন স্টেট) ও `DummyVpnService` বাদ।
- Gradle: AGP 8.7.3-এর সাথে মানানসই Gradle 8.10.2, Compose বাদ, লিন্ট abortOnError বন্ধ।

> `targetSdk=28` ইচ্ছাকৃত — Android 10+ এ বেশি হলে অ্যাপের স্টোরেজ থেকে বাইনারি চালানো যায় না। তাই Play Store-এর জন্য নয়, সাইডলোডের জন্য।

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

