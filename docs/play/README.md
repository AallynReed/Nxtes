# Publishing Nxtes on Google Play

Everything Play Console asks for, in the order it asks. Store text and graphics are in
[listing.md](listing.md) and [graphics/](graphics/).

| | |
|---|---|
| Package | `net.mildhope.nxtes` |
| Privacy policy | https://nxtes.mildhope.net/privacy |
| Account deletion | https://nxtes.mildhope.net/delete-account |
| Bundle | `nxtes-<version>.aab`, attached to each GitHub release from 1.2.1 |

## 1. Create the app

Play Console > Create app: name `Nxtes`, default language English (United States), App, Free.
Accept the declarations.

## 2. App signing (before the first upload)

Give Play the existing release key, so Play installs and GitHub APKs update each other.

1. Test and release > Setup > App signing > Choose signing key > Use a different key > Export and
   upload a key from Java keystore. Download `pepk.jar` and the encryption public key.
2. Run this from `android\` (it asks for the keystore and key passwords from
   `keystore.properties`):

   ```powershell
   & "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe" -jar "$env:USERPROFILE\Downloads\pepk.jar" --keystore=nxtes-release.jks --alias=nxtes --output=nxtes-play-key.zip --include-cert --rsa-aes-encryption --encryption-key-path="$env:USERPROFILE\Downloads\encryption_public_key.pem"
   ```

3. Upload `nxtes-play-key.zip`, then delete it. The bundle is signed with the same key, so it's
   also the upload key.

## 3. Closed test (required for new personal accounts)

Production access needs a closed test with at least 12 testers who opt in and stay opted in for 14
days in a row.

1. Test and release > Testing > Closed testing > Create track (or use Alpha).
2. Testers: an email list of at least 12 Google accounts. Share the opt-in link; each tester opens it,
   accepts, and installs Nxtes from Play. Installing a GitHub APK doesn't count.
3. Create a release, upload the `.aab`, release notes from the GitHub release, then send it for review.
4. After 14 days, Dashboard > Apply for production, describing what testers did and what changed.

## 4. App content (Policy > App content)

**Privacy policy:** `https://nxtes.mildhope.net/privacy`

**App access:** All functionality is available without special access. Sync is optional and needs a
free account, which anyone can create in the app with an email address. If review asks for a test
account, create one on the server and give its email and password here:
`docker exec nxtes nxtes-server admin create-user <email> <password>`.

**Ads:** No.

**Content rating:** questionnaire category "Utility, productivity, communication or other". No
violence, sexuality, language, controlled substances, gambling or purchases. Users can't chat with
or find each other. A share link sends the user's own exported PDF to people they choose, like any
file export.

**Target audience:** 13–15, 16–17 and 18 and over. Not designed for children; it doesn't appeal to
children under 13.

**News app:** No. **COVID-19 tracing:** No. **Government app:** No. **Financial features:** None.
**Health:** None.

**Data safety:**

- Collects or shares required user data: Yes
- All collected data encrypted in transit: Yes
- Users can request deletion: Yes (account deletion URL above, and in the app)
- Account creation: email and password. Delete URL: `https://nxtes.mildhope.net/delete-account`

| Data type | Collected | Shared | Optional | Purposes |
|---|---|---|---|---|
| Personal info > Email address | Yes | No | Yes (sync only) | App functionality, Account management |
| Files and docs | Yes | No | Yes (sync only) | App functionality |
| Photos and videos > Photos | Yes | No | Yes (sync only) | App functionality |
| Audio > Voice or sound recordings | Yes | No | Yes (sync only) | App functionality |
| App info and performance > Diagnostics | Yes | No | Yes (handwriting conversion only) | Analytics |

The diagnostics row is Google ML Kit, which reports the recognition language, device and app details
and performance when writing is turned into text. Writing itself stays on the device. Nothing is
processed ephemerally; nothing is sold or used for ads.

**Foreground service permissions:** declare `FOREGROUND_SERVICE_MICROPHONE`.

- Use: Audio recording while writing. The user taps Record in a document; the recording keeps going
  when they switch apps or the screen turns off, and stops when they tap Stop or the notification's
  Stop action. Without a foreground service, Android would cut the recording off.
- Video: upload [graphics/microphone-service.mp4](graphics/microphone-service.mp4) to YouTube as
  unlisted and paste the link.

## 5. Store listing

Grow users > Store presence > Main store listing: text from [listing.md](listing.md), then add
Portuguese (Portugal) as a translation. Category: Productivity. Tags: Note taking, PDF, Education.
Contact email: `nxtes@mildhope.net`. Website: `https://github.com/AallynReed/Nxtes`.

## 6. Releases after launch

Bump `versionCode` and `versionName`, build both files, and upload the `.aab` to the track:

```powershell
cd android; .\gradlew.bat assembleRelease bundleRelease
```

The APK goes on the GitHub release as before; both are signed with the same key.

## Notes

- Native libraries: the 64-bit ones are 16 KB page aligned, as Play requires for Android 15 and
  later. The 32-bit ones aren't, which is fine because 32-bit devices use 4 KB pages.
- Nxtes is laid out for tablets. Phones can install it, but some screens are cramped.
