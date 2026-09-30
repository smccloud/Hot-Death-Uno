# Release signing

How to give the `release` variant a real signing key instead of the debug key it
uses today, and how to keep the key itself out of git.

**Where this stands:** `app/build.gradle` signs the release variant with
`signingConfigs.debug`, because this project has no release keystore and an
unsigned APK cannot be installed — there is nothing to publish without one. The
release build is otherwise real: R8-minified, no `g` suffix on the version name.
Everything below is the swap.

## Before you start: this key is unrecoverable

One key, for the life of the application. Android identifies an app by its
signing certificate, so:

- Lose the keystore and you can never ship an update to an existing install. On
  Google Play the only remedy is a new application ID, which is a new listing
  from zero.
- Back it up somewhere you will not lose before you do anything else — an
  encrypted cloud folder or a password-manager file attachment. The passwords
  go in the same place.
- Do not commit it, and do not paste it into a Jenkinsfile, a `properties` file
  that is tracked, or this repository in any form.

`app/build.gradle` is the only place the key is *referenced*. The key itself never
needs to be in the repo.

## 1. Create the key

`keytool` ships with the JDK; on this machine it is `/usr/bin/keytool` (JDK 21).

```bash
keytool -genkeypair \
  -keystore ~/hotdeath-release.jks \
  -storetype PKCS12 \
  -alias hotdeath \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=Hot Death UNO, O=, L=, ST=, C=US"
```

Notes on the flags:

- **`-storetype PKCS12`** is the modern default and is what Android Studio
  generates. It cannot hold two different passwords, so the key password is the
  store password — expect `-keypass` to be ignored with a warning. Use
  `-storetype JKS` instead if you genuinely want them to differ; AGP handles both.
- **`-keysize 4096`** exceeds the 2048 minimum that Play asks for.
- **`-validity 10000`** is about 27 years, expiring around 2053. Play requires the
  key to stay valid past 2033-10-22, and AGP warns below 25 years.
- **`-dname`** is the certificate's subject. Put a real organisation and location
  in if this ever goes to Play; the CN is what shows up as the app's publisher.

Keep the alias (`hotdeath`) — it is referenced in `keystore.properties` below.

## 2. Close the `.gitignore` gap

`.gitignore` already ignores `*.jks` and `*.keystore`, so the key file is covered.
`keystore.properties` is not: it has no extension, so neither rule matches it, and
it is the file most likely to be committed by accident because it looks like
ordinary configuration. Add to the existing "Signing material" block:

```gitignore
keystore.properties
*.properties.asc
```

**Unanchored, deliberately.** The properties file lives in the Gradle module at
`com.smccloud.hotdeath/app/`, not at the repo root, so a leading slash —
`/keystore.properties` — would match nothing that matters and leave four
plaintext passwords tracked. `.gitignore` already has a note about this trap for
the build-output patterns; the same reasoning applies here. Verify before you
trust it:

```bash
git check-ignore -v com.smccloud.hotdeath/app/keystore.properties
```

## 3. Wire it into `app/build.gradle`

Add this **above** the `android {` block:

```groovy
// Release signing material, read from keystore.properties beside this file when
// it exists. That file is gitignored and holds the four secrets, so nothing
// about the key lives in git. Without it the release variant falls back to the
// debug key, which keeps a fresh clone buildable and keeps CI's release stage
// from failing for anyone who has not set one up.
def keystoreProperties = new Properties()
def keystorePropertiesFile = file('keystore.properties')
def hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystorePropertiesFile.withInputStream { keystoreProperties.load(it) }
}
```

Add a `signingConfigs` block inside `android { }`:

```groovy
    signingConfigs {
        // Declared only when the properties file is there, so an empty signing
        // config never reaches AGP's validation.
        if (hasReleaseKeystore) {
            release {
                storeFile file(keystoreProperties['storeFile'])
                storePassword keystoreProperties['storePassword']
                keyAlias keystoreProperties['keyAlias']
                keyPassword keystoreProperties['keyPassword']
            }
        }
    }
```

Then change the last line of the `release` build type, replacing
`signingConfig signingConfigs.debug`:

```groovy
            signingConfig hasReleaseKeystore ? signingConfigs.release : signingConfigs.debug
```

The fallback is the point: a clone with no keystore still produces an installable
release APK rather than failing the build, which matters because the Jenkinsfile's
`Assemble release` stage is unconditional.

## 4. Create `keystore.properties`

Put it beside `build.gradle`, at `com.smccloud.hotdeath/app/keystore.properties`.
`storeFile` resolves relative to the module directory, so a bare filename means
"next to this properties file":

```properties
storeFile=hotdeath-release.jks
storePassword=<the password keytool asked for>
keyAlias=hotdeath
keyPassword=<the same password under PKCS12>
```

Copy the key itself there too, so the two travel together locally:

```bash
cp ~/hotdeath-release.jks com.smccloud.hotdeath/app/
chmod 600 com.smccloud.hotdeath/app/hotdeath-release.jks
```

## 5. Verify it took

```bash
cd com.smccloud.hotdeath && gradle :app:signingReport
```

The `variant: release` row must show `hotdeath`, your `-dname` subject, and a
SHA-256 certificate — **not** `CN=Android Debug`. If it still reports Debug, the
properties file is not being found at the path `file('keystore.properties')`
resolves to.

To check the built APK itself, use whichever build-tools revision is installed
(CI pins 35.0.0):

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 6. Getting the key onto the Jenkins controller

The controller has the `credentials-binding` and `plain-credentials` plugins but
**not** `file-credentials`, so `withCredentials([file(...)])` will not resolve
there. Two approaches that do work with what is installed.

**Base64 in a secret-text credential** — self-contained, no new plugins. In the
job's credentials add:

| ID | Kind | Value |
| --- | --- | --- |
| `hotdeath-release-jks` | Secret text | output of `base64 -w0 ~/hotdeath-release.jks` |
| `hotdeath-release-signing` | Secret text | the four `KEY=VALUE` lines from step 4 |

Then add a stage **before** `Assemble release`. Note it is deliberately *not*
wrapped in `dir(moduleDir)`, because the paths below have to resolve against the
workspace root to land in the module directory:

```groovy
  stage('Signing material') {
    withCredentials([
      string(credentialsId: 'hotdeath-release-jks', variable: 'HOTDEATH_JKS_B64'),
      string(credentialsId: 'hotdeath-release-signing', variable: 'HOTDEATH_SIGNING'),
    ]) {
      sh """
        set -eu
        mkdir -p app/keystore
        printf '%s' "\$HOTDEATH_JKS_B64" | base64 -d > app/keystore/hotdeath-release.jks
        chmod 600 app/keystore/hotdeath-release.jks
        printf '%s\n' "\$HOTDEATH_SIGNING" > app/keystore/keystore.properties
      """
    }
  }
```

Jenkins masks both values in the console log, so neither appears in the build
output. Removing the expanded `keystore.properties` afterwards is not optional
housekeeping, though: it is a plaintext password sitting in the workspace, and this
job's SCM config does not pin a clean-before-checkout behaviour, so do not rely on
the next build tidying it away for you. Add:

```groovy
  post {
    always {
      sh 'rm -f app/keystore/keystore.properties app/keystore/hotdeath-release.jks'
    }
  }
```

**Or** skip credentials for the file itself: place the `.jks` at a fixed path on
the controller — `$JENKINS_HOME/.toolcache/hotdeath/hotdeath-release.jks`,
`chmod 600`, owned by the `jenkins` user — and keep only the passwords in a
secret-text credential, expanding them into `keystore.properties` in the same
stage. Fewer moving parts, at the cost of relying on filesystem permissions as
the protection for the key itself.

Either way, the `Assemble release` stage needs no change: it runs
`gradle assembleRelease` in the module directory, and `build.gradle` picks the
material up.

## 7. What this does to 1.1.143

Release **1.1.143** is published at
<https://github.com/smccloud/Hot-Death-Uno/releases/tag/v1.1.143>, debug-signed.
The first properly-signed APK will carry a **different signing certificate**, and
Android refuses to install it as an update over 1.1.143: anyone who installed that
build has to uninstall first, losing their saved game, and it could not be uploaded
to Play over the debug-signed one either.

That is only a problem while debug-signed builds are in the wild, and it is the
reason the Play rule is "the first key you upload is the only key you will ever
use". Worth knowing before anyone installs 1.1.143 expecting later updates to
land on top of it.
