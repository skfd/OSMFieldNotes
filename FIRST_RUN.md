# First Run In Android Studio

This guide will help you set up and run the `OSM Field Notes` project in Android Studio for the first time.

## Prerequisites

Before you begin, ensure you have the following installed:

1.  **JDK 17+**: Required for building the project.
2.  **Android Studio Hedgehog (2023.1.1)** or later.
3.  **Android SDK (API 34)**: You can install this via the Android Studio SDK Manager.

## Step 1: Launch Android Studio

You can launch Android Studio manually or use the provided helper script if it matches your installation path.

### Using the Helper Script
The project includes a `start-studio.bat` script which attempts to launch Android Studio from the default location:
`C:\Program Files\Android\Android Studio\bin\studio64.exe`

Double-click `start-studio.bat` to run it. If your Android Studio is installed elsewhere, you can edit this file to point to your `studio64.exe`.

### Manual Launch
Simply open Android Studio from your Start Menu or desktop shortcut.

## Step 2: Open the Project

1.  In Android Studio, select **File > Open**.
2.  Navigate to the directory where you cloned the repository (e.g., `C:\Users\YourName\Code\OSM Field Notes`).
3.  Select the root folder (containing `build.gradle.kts` and `settings.gradle.kts`) and click **OK**.

## Step 3: Configure SDK Path (Crucial)

Android Studio requires a `local.properties` file to key the location of your Android SDK. This file is not version-controlled.

1.  Android Studio may automatically create this file for you during the first sync.
2.  If the build fails with an error about `SDK location not found`, create or edit the `local.properties` file in the project root.
3.  Add the `sdk.dir` property pointing to your SDK location (note the double backslashes for Windows paths):

```properties
sdk.dir=C:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
```

*Tip: You can find your SDK path in Android Studio under **Settings > Languages & Frameworks > Android SDK**.*

### OSM login (optional)

Logging in to OpenStreetMap needs an OAuth 2 client id in the same file. Register one at
`https://www.openstreetmap.org/oauth2/applications/new` with redirect URI
`osmfieldnotes://oauth/callback` (exactly), "Confidential application" unchecked, and the
`write_notes` and `read_prefs` permissions; then add:

```properties
OSM_OAUTH_CLIENT_ID=paste_the_client_id_here
```

`local.properties.example` lists the optional keys (`OSM_OAUTH_REDIRECT_URI`,
`OSM_OAUTH_BASE_URL` for the sandbox). Without a client id the app builds and runs; the
login button just says the id is missing.

### Release signing (only for publishing)

A release build (`assembleRelease`) is minified and, when a keystore is configured, signed.
Without one it still builds, as `app-release-unsigned.apk`. Create the keystore **outside the
repo** once, with the keytool that ships in the Android Studio JBR:

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
    -keystore "$env:USERPROFILE\keys\osmfieldnotes-release.jks" -storetype PKCS12 `
    -alias osmfieldnotes -keyalg RSA -keysize 4096 -validity 10000 `
    -dname "CN=OSM Field Notes, O=skfd, C=CA"
```

It asks for one password; PKCS12 uses it for both the store and the key. Back that file up
somewhere safe: every update must be signed with the same key, and it cannot be recovered.
Then add to `local.properties` (or export the same four names as environment variables):

```properties
RELEASE_STORE_FILE=C\:\Users\YourName\keys\osmfieldnotes-release.jks
RELEASE_STORE_PASSWORD=the_password
RELEASE_KEY_ALIAS=osmfieldnotes
RELEASE_KEY_PASSWORD=the_password
```

`*.jks`, `*.keystore` and `*.p12` are gitignored; never put the keystore or the passwords in
the repo. RELEASE.md has the rest of the release checklist.

## Step 4: Sync Gradle

1.  Click the **Sync Project with Gradle Files** button (elephant icon) in the toolbar.
2.  Wait for the sync to complete. This may take a few minutes as it downloads dependencies.

## Step 5: Set Up an Android Virtual Device (AVD)

If you don't have a physical device, you'll need an emulator.

1.  Go to **Tools > Device Manager**.
2.  Click **+** or **Create Device**.
3.  Select a device definition (e.g., Pixel 7) and click **Next**.
4.  Select a system image (API 34 is recommended) and click **Next**.
5.  Click **Finish**.

## Step 6: Build and Run

1.  Select your deployment target (the AVD you created or a connected device) from the dropdown in the toolbar.
2.  Click the green **Run** button (or press `Shift+F10`).
3.  Approve any installation prompts on the device/emulator.

## Troubleshooting

-   **"SDK location not found"**: Check your `local.properties` file as described in Step 3.
-   **Java version mismatch**: Go to **Settings > Build, Execution, Deployment > Build Tools > Gradle** and ensure "Gradle JDK" is set to JDK 17 or newer.
-   **Emulator won't start**: Ensure HAXM or Hyper-V is enabled in your Windows features.
