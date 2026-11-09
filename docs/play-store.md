# Releasing on Google Play

Everything here is ready except the parts that need your Google Play developer account.

## 1. Make the upload key (once)

Keep the key and its passwords somewhere safe. If you lose them, Play App Signing can reset the
upload key, but only through Play support.

```bash
keytool -genkeypair -v -keystore cappuccino-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

Then create `keystore.properties` next to `settings.gradle.kts`. Git ignores both files.

```
storeFile=cappuccino-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

## 2. Build the bundle

```bash
./gradlew :app:bundleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. Without `keystore.properties`, it's
signed with the debug key, which Play rejects, so a missing key can't be uploaded by mistake.
Raise `versionCode` in `app/build.gradle.kts` for every upload.

## 3. In Play Console

1. Create the app: name **Cappuccino**, free, app (not game).
2. Turn on Play App Signing and upload the bundle to **Internal testing** first.
3. Fill in the store listing below, the content rating questionnaire, and the data safety form.
4. Add the privacy policy link: publish [privacy.md](privacy.md) anywhere public, such as GitHub Pages.
5. In **Device catalog**, exclude phones. The studio is built for tablets, and on a phone its
   toolbars overlap.
6. New personal developer accounts must run a closed test with at least 12 testers for 14 days
   before production.

## Store listing

**App name:** Cappuccino

**Short description (80 characters at most):**
A free, ad-free drawing app with a cute companion that gives you art feedback.

**Full description:**

Cappuccino is a free illustration app for Android tablets, with no ads, accounts, or tracking.

Draw with a pen that feels immediate: sixteen brushes, scatter brushes for leaves and rocks,
smudge, fill, gradients, layers with masks and blend modes, symmetry, perspective guides,
selections with soft edges, and perspective transforms. Animate with frames and onion skin.

A little companion keeps you company. It looks at your values, palette, composition, and
silhouette, compares your work with your references, and leaves a small note when it has an idea.
It shows its reasoning, points to the spot on your canvas, and suggests five-minute warm-ups.
When you'd rather be alone, it hides behind the toolbar and peeks.

Everything runs on your device. Your art never leaves it unless you export or share it.

Export PNG, layered PSD, time-lapse videos, and animations.

**Category:** Art & Design

**Graphics:**
- Feature graphic (1024 × 500): `marketing/banner-1024x500.png`
- Screenshots: `docs/screenshots/` (use landscape tablet shots for the 10-inch tablet slot)
- App icon (512 × 512): `marketing/icon-512.png`

## Data safety answers

- Does the app collect or share user data? **No.**
- The app has no internet permission, no analytics, and no ads.
- Files you export go to Pictures, Movies, or Download, which you choose to do.

## Content rating

Answer the questionnaire as a drawing tool with no user-to-user communication, no purchases,
and no violent, sexual, or gambling content. It should rate as Everyone / PEGI 3.

## Later: the Flat white support purchase

The Flat white companion is free for now. Selling it for €2 needs a one-time in-app product in
Play Console and the Google Play Billing library in the app. That adds the billing permission
and a network connection to Play, so the data safety answers above would change slightly.
