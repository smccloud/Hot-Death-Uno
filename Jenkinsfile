// Hot Death Uno - CI
// Scripted syntax on purpose: this Jenkins lacks pipeline-model-definition,
// so `pipeline { agent any }` will not parse. Controller is Linux, so `sh`.
// Plugins assumed (all verified installed on the instance): workflow-cps,
// workflow-basic-steps, workflow-durable-task-step, workflow-scm-step,
// workflow-support, script-security.
//
// DESIGN NOTE -- why almost nothing is done in Groovy here:
// Earlier failures all came from Groovy code touching Jenkins objects inside the
// script-security sandbox. Top-level `def` constants are locals of run(), not
// fields, so helper methods cannot read them. And env.SOME_VAR does not
// necessarily return a String -- when unset it returns an
// UninstantiatedDescribableWithInterpolation, so calling .exists() on it throws.
// Keep Groovy to stage ordering; put real logic in `sh` with returnStdout.

// Keep only the 10 most recent builds. Set outside `node` so it applies even if
// no executor is free. Written as raw $class rather than the usual
// buildDiscarder(logRotator(...)) because that symbol is registered by
// pipeline-model-definition, which this instance does not have. Both classes
// below are Jenkins core, and the `properties` step comes from the Pipeline: Job
// plugin -- mandatory for a "script from SCM" job to exist at all.
properties([[$class: 'BuildDiscarderProperty',
              strategy: [$class: 'LogRotator', numToKeepStr: '10']],
             // Build first, then test. RUN_TESTS=false stops once the signed
             // release APK exists: no unit tests, no lint, no emulator, no
             // JUnit report.
             //
             // This was introduced defaulted to false, so the Gradle 8.13 ->
             // 9.5.0 bump could be shown to build and sign on its own rather
             // than being reported as a failure of a half-hour emulator matrix.
             // Build #66 did exactly that: Gradle 9.5.0 fetched and checksum
             // verified, both variants assembled, release signed with the real
             // key, artifacts archived, about 100 seconds, no test stages.
             //
             // So it is now defaulted to true and the matrix runs again. The
             // parameter stays rather than being removed: it is the switch that
             // separates "does it build" from "does it pass", which is worth
             // having on the next toolchain change, and it is the only way to
             // get a signed artifact out when the matrix is the thing that is
             // broken.
             [$class: 'ParametersDefinitionProperty',
              parameterDefinitions: [
                [$class: 'BooleanParameterDefinition',
                 name: 'RUN_TESTS',
                 defaultValue: true,
                 description: 'Run unit tests, lint, and the API 34-36 emulator matrix. Off = build and sign only.'],
                // Screenshots are a documentation asset, not a gate, so they are
                // off by default: they cost a whole extra emulator boot and
                // nothing in the build depends on them. Turn it on when the
                // README images actually need refreshing, which should be the
                // only time they are ever regenerated -- a screenshot that
                // changes because someone re-ran a build is churn.
                [$class: 'BooleanParameterDefinition',
                 name: 'SCREENSHOTS',
                 defaultValue: false,
                 description: 'Boot an emulator, drive the app, and archive the README screenshots. Off = skip entirely.']
              ]]])

node {
  def moduleDir = 'com.smccloud.hotdeath'
  // 9.5.0, not the 9.8.0 that is current: Kotlin 2.4.20 below supports Gradle
  // 7.6.3-9.5.0 and no further, so 9.5.0 is the top of what the Kotlin plugin
  // will accept here. Verified against this exact project before pinning it:
  // 140/140 unit tests, both variants, lint and the instrumented sources all
  // green on 9.5.0.
  def gradleVersion = '9.5.0'
  def api = '36'

  // Every Gradle invocation gets these, and they are defined once so they cannot
  // drift apart -- there are five invocations and a flag added to four of them
  // is worse than not adding it at all.
  //
  // --project-cache-dir moves the whole project-local Gradle cache out of the
  // workspace and into the toolcache, which is the only thing here that survives
  // the Workspace cleanup stage. It has to: Gradle keeps the configuration cache
  // state in <project>/.gradle/configuration-cache, in the project directory and
  // not in the Gradle user home, so a wiped workspace deletes it before the next
  // build can read it.
  //
  // This is a relocation rather than a copy. An earlier attempt at this preserved
  // the one directory by stashing it in /tmp and copying it back, and the files
  // arrived intact -- 20 files, 400K, a complete entry -- while Gradle still
  // reported "no cached configuration is available" on every run. Copying a cache
  // in and out is evidently not the same thing to Gradle as never having moved it,
  // and it is one more thing to go wrong for no gain. Pointing the cache at a
  // directory that is not deleted achieves the same reuse with nothing to stash,
  // restore or verify.
  //
  // The literal path matches the rest of this file, which already hardcodes
  // /var/lib/jenkins in the cleanup stage's guard.
  def gradleArgs = '--no-daemon --stacktrace --project-cache-dir=/var/lib/jenkins/.toolcache/hotdeath/project-cache'

  // AGP 8.11's *default* build-tools revision. This project never sets
  // buildToolsVersion, so AGP resolves 35.0.0 no matter how new the installed
  // revision is.
  def buildTools = '35.0.0'
  def sdkHome
  def gradleHome
  // `params` is the right source for a declared parameter, and the comparison
  // covers the three states this can actually be in: true, false, or absent on
  // a run queued before the parameter was added.
  def runTests = (params.RUN_TESTS == true) || (params.RUN_TESTS == 'true')
  echo "RUN_TESTS=${runTests} (false = build and sign only)"
  // Same three states as RUN_TESTS above, including absent on a run queued
  // before the parameter existed.
  def screenshots = (params.SCREENSHOTS == true) || (params.SCREENSHOTS == 'true')
  echo "SCREENSHOTS=${screenshots}"

  // Destroys the signing material, wherever it happens to be. Defined once and
  // called at both ends of the run: the pre-clean and the teardown have to agree
  // exactly about these paths, and #50 is what it costs when they do not
  // disagree -- a real keystore and a real plaintext password file left on the
  // controller for the life of the workspace, with the teardown removing a path
  // that had never existed. Reads storeFile out of the properties file first,
  // because the keystore's location is defined by it rather than by convention.
  def removeSigningMaterial = {
    dir(moduleDir) {
      sh '''
        set -u
        rm -rf ../../app/keystore app/keystore
        STALE=$(sed -n 's/^storeFile=//p' app/keystore.properties 2>/dev/null | head -1 || true)
        case "$STALE" in
          '') ;;
          /*) rm -f "$STALE" ;;
          *) rm -f "app/$STALE" ;;
        esac
        rm -f app/keystore.properties app/.signing.raw
        # Also sweep the module dir. The rm above only knows the path the current
        # properties name, so a keystore left by a run whose storeFile was
        # different would survive; nothing checked out ends in .jks, since every
        # keystore is gitignored and CI-only, so this cannot eat anything real.
        rm -f app/*.jks
      '''
    }
  }

  // Wipe the workspace before checking anything out, because this job's SCM
  // config does not pin clean-before-checkout and without this every run
  // inherits the last run's build/ directory.
  //
  // That is not just untidy, it hides things. Gradle then reports most tasks
  // up-to-date and never re-runs them, so a change that ought to be recompiled
  // can be skipped and the build still reports SUCCESS. Build #67 is the
  // example: 34 of 34 actions up-to-date on assembleDebug, and the release APK
  // archived was the one #66 had produced. Nothing was wrong with it, but the
  // build was not evidence of anything either.
  //
  // Deliberately the first stage and not a teardown stage. Cleaning on the way
  // out means a failed build leaves its residue for the next one to trip over,
  // which is the case that actually matters. Cleaning on the way in means the
  // worst a crash can leave behind is a half-deleted tree, which the next run
  // finishes deleting anyway.
  //
  // The expensive things are not in the workspace and are not touched: the
  // Gradle distribution, the Android SDK, the AVDs, the system images and the
  // Gradle project cache all live under $JENKINS_HOME/.toolcache/hotdeath, and
  // the dependency cache is in the Gradle user home outside the tree. So this
  // costs a full recompile and a full R8 pass -- tens of seconds -- and keeps
  // the downloads.
  //
  // This stage still deletes everything, and that is deliberate even now that
  // the configuration cache is enabled. Two things were tried, and the order
  // matters:
  //
  // 1. Enabling org.gradle.configuration-cache alone. Its state lives at
  //    <project>/.gradle/configuration-cache -- in the project directory, not in
  //    the Gradle user home -- so this stage deleted it before every build.
  //    Every run stored an entry and never hit one: the write cost with none of
  //    the benefit, which is slower than not enabling it at all.
  //
  // 2. Preserving that one directory by stashing it in /tmp and copying it back.
  //    The files arrived intact -- 20 of them, 400K, a complete entry with
  //    entry.bin and buildfingerprint.bin -- and Gradle still reported "no
  //    cached configuration is available" on every single run. Copying a cache
  //    in and out is evidently not the same thing to Gradle as never having
  //    moved it. Builds #75 and #76 measured 1m05s and 1m02s against #73's
  //    1m04s: no change, because there were no hits to save anything on.
  //
  // So the cache is moved out of the workspace altogether with
  // --project-cache-dir, see gradleArgs above, and this stage goes back to
  // deleting everything with nothing preserved. The check below is therefore
  // the original one and unchanged in behaviour: it exists to catch a wipe that
  // did not finish -- an undeletable file, a mount point, a permission problem
  // -- which is the only kind of leftover it can now see, since everything in
  // the workspace is removed before it runs.
  stage('Workspace cleanup') {
    sh '''
      set -eu
      # Refuse to delete anything unless we really are in a workspace. A stage
      # that silently rm -rf'd the wrong directory would be far worse than a
      # build that fails, and $WORKSPACE being empty is exactly the case where
      # "." would otherwise be something alarming.
      #
      # Exact matches only, no trailing glob. The workspace on this controller
      # is /var/lib/jenkins/workspace/Hot-Death-Uno, so a "/var/lib/jenkins/"*
      # pattern matches the one directory that is safe to clean and the stage
      # refuses to run on every build.
      case "$WORKSPACE" in
        ""|"/"|"/var/lib/jenkins"|"/var/lib/jenkins/")
          echo "refusing to clean: WORKSPACE is '$WORKSPACE'" >&2
          exit 1
          ;;
      esac
      echo "workspace: $WORKSPACE"
      BEFORE=$(du -sh . 2>/dev/null | awk '{print $1}' || echo '?')
      echo "before cleanup: $BEFORE"
      find . -mindepth 1 -maxdepth 1 -print | cut -c3- | sort >&2 || true
      # -exec rm -rf rather than rm -rf ./* : the glob does not match dotfiles
      # and fails outright on an empty directory.
      find . -mindepth 1 -maxdepth 1 -exec rm -rf {} +
      LEFT=$(find . -mindepth 1 -maxdepth 1 | wc -l)
      echo "after cleanup: $LEFT entries remain"
      if [ "$LEFT" != '0' ]; then
        echo "ERROR: workspace is not empty after cleanup" >&2
        exit 1
      fi
    '''
  }

  stage('Checkout') {
    checkout scm
  }

  stage('Toolchain') {
    sh 'java -version 2>&1 | head -n 3'
  }

  // An SDK already exists on this controller at $ANDROID_HOME
  // (/var/lib/jenkins/android-sdk) with cmdline-tools but without
  // platforms;android-36. So: adopt any existing SDK, then ensure the required
  // packages are present regardless of how old it is. sdkmanager is idempotent,
  // so running it unconditionally is cheap.
  stage('Android SDK') {
    sdkHome = sh(
      script: """
        set -euo pipefail
        CACHE="\${JENKINS_HOME:-/var/lib/jenkins}/.toolcache/hotdeath"
        SDK=""
        for c in "\${ANDROID_HOME:-}" "\${ANDROID_SDK_ROOT:-}" "\$CACHE/android-sdk"; do
          if [ -n "\$c" ] && [ -d "\$c" ]; then SDK="\$c"; break; fi
        done
        if [ -z "\$SDK" ]; then SDK="\$CACHE/android-sdk"; fi
        mkdir -p "\$SDK"
        echo "using SDK: \$SDK" >&2

        SDKMANAGER=""
        for cand in "\$SDK/cmdline-tools/latest/bin/sdkmanager" \\
                    "\$SDK"/cmdline-tools/*/bin/sdkmanager \\
                    "\$SDK/tools/bin/sdkmanager"; do
          if [ -x "\$cand" ]; then SDKMANAGER="\$cand"; break; fi
        done

        if [ -z "\$SDKMANAGER" ]; then
          echo "installing cmdline-tools" >&2
          curl -fsSL -o /tmp/cmdtools.zip \\
            'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
          unzip -q -o /tmp/cmdtools.zip -d /tmp/cmdtools
          rm -rf "\$SDK/cmdline-tools/latest"
          mkdir -p "\$SDK/cmdline-tools"
          mv /tmp/cmdtools/cmdline-tools "\$SDK/cmdline-tools/latest"
          SDKMANAGER="\$SDK/cmdline-tools/latest/bin/sdkmanager"
        fi
        echo "using sdkmanager: \$SDKMANAGER" >&2
        echo "sdk_root: \$SDK" >&2

        yes | "\$SDKMANAGER" --sdk_root="\$SDK" --licenses > /dev/null 2>&1 || true
        # sdkmanager draws a progress bar on STDOUT. With returnStdout:true that
        # would be captured into sdkHome alongside the path, handing Gradle a
        # multi-line ANDROID_HOME. Send it to stderr; only the path stays on stdout.
        "\$SDKMANAGER" --sdk_root="\$SDK" \\
          'platform-tools' 'platforms;android-${api}' 'build-tools;${buildTools}' >&2

        test -d "\$SDK/platforms/android-${api}" || { echo "MISSING platform android-${api}" >&2; exit 1; }
        test -d "\$SDK/build-tools/${buildTools}" || { echo "MISSING build-tools ${buildTools}" >&2; exit 1; }
        echo "\$SDK"
      """,
      returnStdout: true
    ).trim()
    echo "ANDROID_HOME=${sdkHome}"
  }

  stage('Gradle') {
    gradleHome = sh(
      script: """
        set -euo pipefail
        CACHE="\${JENKINS_HOME:-/var/lib/jenkins}/.toolcache/hotdeath"
        G="\$CACHE/gradle-${gradleVersion}"
        if [ ! -x "\$G/bin/gradle" ]; then
          echo "fetching Gradle ${gradleVersion}" >&2
          mkdir -p "\$CACHE"
          curl -fsSL -o "\$G.zip" \\
            'https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip'
          curl -fsSL -o "\$G.zip.sha256" \\
            'https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip.sha256'
        # Gradle publishes a bare 64-char hash with no filename, but
        # `sha256sum -c` requires "<hash>  <file>". So compare manually.
        # Taking awk's first field tolerates either layout.
        EXPECTED=\$(awk '{print \$1}' "\$G.zip.sha256" | tr -d '\\r\\n')
        ACTUAL=\$(sha256sum "\$G.zip" | cut -d' ' -f1)
        if [ "\$EXPECTED" != "\$ACTUAL" ]; then
          echo "CHECKSUM MISMATCH for \$G.zip" >&2
          echo "  expected \$EXPECTED" >&2
          echo "  actual   \$ACTUAL" >&2
          exit 1
        fi
        echo "checksum ok (\$ACTUAL)" >&2
        # The distribution zip has a top-level gradle-<version>/ dir, so without
        # -d it would unpack into the workspace and the cached launcher below
        # would not exist.
        unzip -q -o "\$G.zip" -d "\$CACHE"
        fi
        test -x "\$G/bin/gradle" || { echo "MISSING \$G/bin/gradle" >&2; exit 1; }
        "\$G/bin/gradle" --version >&2
        echo "\$G/bin/gradle"
      """,
      returnStdout: true
    ).trim()
    echo "gradle=${gradleHome}"
  }

  // AGP reads ANDROID_HOME, so local.properties is unnecessary -- which is what
  // we want, since it is gitignored and holds a machine-specific SDK path.
  //
  // The Robolectric tests here (GameOptions, Card.toString, the save/resume JSON
  // round-trips) fetch an android-all-instrumented jar from Maven Central the
  // first time they run, on top of the usual dependency resolution. If this
  // controller ever needs to build without network access, prefetch that jar or
  // switch Robolectric to its offline mode before tightening anything else.
  stage('Assemble') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh "${gradleHome} ${gradleArgs} assembleDebug"
      }
    }
  }

  // Materialise the release signing material into the workspace, then let it be
  // destroyed again below. Two secret-text credentials hold it: the keystore
  // base64-encoded, and the four keystore.properties values. There is no
  // file-credentials plugin on this controller, which is why the key arrives as
  // text rather than as withCredentials([file(...)]).
  //
  // Inside dir(moduleDir), and that is not cosmetic. build.gradle reads
  // file('keystore.properties'), which resolves against the app module's own
  // directory, so the material has to land in com.smccloud.hotdeath/app/. The
  // first version of this stage wrote app/keystore relative to the workspace
  // root instead, which put a real keystore and a real plaintext password file
  // somewhere Gradle never looks: build #50 went green, Gradle saw no
  // keystore.properties, and packageRelease was UP-TO-DATE on the debug-signed
  // APK from before any of this existed. Only the Signing report stage below
  // caught it. Deriving the path from moduleDir instead of writing it out means
  // it cannot disagree with the teardown stage again.
  //
  // Being in the right directory was still not sufficient, which is what #50
  // and #54 between them cost: the properties file has to be at
  // app/keystore.properties, directly beside build.gradle, and not at
  // app/keystore/keystore.properties. file() resolves against the project
  // directory, so the subdirectory version is invisible to it -- the file
  // existed, the checks all passed, and hasReleaseKeystore was still false.
  stage('Signing material') {
    // Forgiving on purpose, and the catch is wide on purpose too. A fork
    // without these credentials should still get a build -- with the release
    // variant falling back to the debug key, which is what it did until now --
    // rather than a red pipeline over a missing secret. Anything that leaves
    // the material present but wrong does get swallowed by the catch, so the
    // flag below rethrows it: the distinction that matters is not "did the
    // stage work" but "was there anything to work with". Credentials absent is
    // a fork without secrets and gets a warning; credentials present but
    // unusable fails the build, because quietly falling back there would
    // publish an artifact signed by the wrong key while looking green.
    def credentialsPresent = false
    try {
      withCredentials([
        string(credentialsId: 'hotdeath-release-jks', variable: 'HOTDEATH_JKS_B64'),
        string(credentialsId: 'hotdeath-release-signing', variable: 'HOTDEATH_SIGNING'),
      ]) {
        credentialsPresent = true
        // Start from nothing before writing anything, and use the same closure
        // the teardown uses so the two cannot drift apart again.
        removeSigningMaterial()
        dir(moduleDir) {
        // Single-quoted, so Groovy interpolates nothing here. Every variable in
        // this block is the shell's, and the previous triple-double-quoted
        // version had to escape each one as a backslash-dollar -- which build
        // #49 caught by parsing the two unescaped $( command substitutions as
        // Groovy. There is nothing to interpolate, so the escaping is not
        // needed in the first place. Written this way, a command substitution
        // cannot be mistaken for a GString by accident.
        sh '''
          set -eu
          # A previous run's material can otherwise be picked up, because this
          # job's SCM config does not pin clean-before-checkout.
          # Properties first, keystore second. The other order looked natural and
          # was wrong: the keystore has to land at whatever path storeFile names,
          # and storeFile is not known until the blob is parsed.
          #
          # The blob arrives as one string, and it is easy to create it as a
          # single space-separated line. Properties.load() reads that as one key
          # whose value swallows the rest, so Gradle would find no passwords at
          # all and fail inside R8. Normalise both shapes to newline-separated
          # pairs, splitting only on the four known key names -- never on a bare
          # '=' -- so a password containing an equals sign cannot be cut in half.
          # The \\n is doubled because Groovy unescapes it inside these triple
          # single quotes: written once, it reached sed as a real newline and
          # split the expression across lines, which sed called an unterminated
          # s command.
          #
          # The trailing whitespace strip is not cosmetic: the spaces that
          # separated the pairs land at the end of every value, and
          # Properties.load() trims leading whitespace but keeps trailing, so
          # storeFile would miss its file and the password would simply be wrong
          # -- a failure pointing nowhere near the cause. That strip is a separate
          # sed pass because in the pass that inserts the newlines, `$` anchors to
          # the end of the whole pattern space rather than to each line, so it
          # would only ever reach the last pair.
          PROPS=app/keystore.properties
          RAW_PROPS=app/.signing.raw
          printf '%s\n' "$HOTDEATH_SIGNING" > "$RAW_PROPS"
          chmod 600 "$RAW_PROPS"
          tr -d '\\r' < "$RAW_PROPS" | sed -e 's/storeFile=/\\nstoreFile=/' -e 's/storePassword=/\\nstorePassword=/' -e 's/keyAlias=/\\nkeyAlias=/' -e 's/keyPassword=/\\nkeyPassword=/' | sed -e 's/[[:space:]]*$//' -e '/^$/d' > "$PROPS"
          rm -f "$RAW_PROPS"
          chmod 600 "$PROPS"
          # Name the keys that are missing. Never their values, and never any
          # fragment of the credential: build #51 printed the whole blob into
          # the console log by echoing a value cut out of it, and Jenkins' log
          # masking only covers the exact secret text, so a fragment slips past
          # it. Nothing derived from that credential is echoed from here on.
          MISSING=
          for KEY in storeFile storePassword keyAlias keyPassword; do
            grep -q "^${KEY}=" "$PROPS" || MISSING="${MISSING} ${KEY}"
          done
          if [ -n "$MISSING" ]; then
            echo "ERROR: keystore.properties is missing:${MISSING}"
            exit 1
          fi
          # Honour storeFile rather than imposing a path on it. build.gradle
          # calls file(storeFile) from the app module, so a relative storeFile
          # resolves against that directory -- and #53 is what insisting on
          # a fixed path instead looked like: the credential
          # says hotdeath-release.jks, which is a perfectly good answer, and the
          # stage refused it. Write the key where Gradle will look for it.
          STORE_FILE=$(sed -n 's/^storeFile=//p' "$PROPS" | head -1)
          case "$STORE_FILE" in
            /*) RESOLVED_STORE="$STORE_FILE" ;;
            *) RESOLVED_STORE="app/$STORE_FILE" ;;
          esac
          mkdir -p "$(dirname "$RESOLVED_STORE")"
          # Report the shape of what arrived before decoding it, because
          # `base64: invalid input` on its own says nothing useful. Three facts
          # that between them localise the fault: the length, its remainder mod
          # 4, and how many characters are not in the alphabet at all -- which
          # distinguishes a mangled paste from a credential holding a path or a
          # command. Counts only, never the value.
          #
          # On the remainder: 0 means padded, 2 and 3 are both legitimate final
          # groups, and only 1 is impossible -- the last group can hold 2, 3 or 4
          # characters and never 1. Build #48 hit exactly that, with 5801
          # characters, so the one number worth printing is the one that can only
          # mean a broken value.
          B64_LEN=$(printf '%s' "$HOTDEATH_JKS_B64" | tr -d '\\n\\r' | wc -c)
          B64_BAD=$(printf '%s' "$HOTDEATH_JKS_B64" | tr -d 'A-Za-z0-9+/=\\n\\r' | wc -c)
          echo "keystore credential: ${B64_LEN} base64 characters, $(( B64_LEN % 4 )) mod 4 (0 padded, 2 or 3 unpadded, 1 impossible), ${B64_BAD} outside the alphabet"
          # -d accepts wrapped, unwrapped and unpadded base64, so the credential
          # can hold any of the three. Empty is called out separately, because
          # empty base64 decodes to an empty file without complaint and then
          # surfaces much later as an opaque Gradle keystore error.
          if [ -z "$HOTDEATH_JKS_B64" ] || ! printf '%s' "$HOTDEATH_JKS_B64" | base64 -d > "$RESOLVED_STORE"; then
            echo 'ERROR: the keystore credential did not decode to a keystore -- the line above is why'
            exit 1
          fi
          chmod 600 "$RESOLVED_STORE"
          if [ ! -s "$RESOLVED_STORE" ]; then
            echo 'ERROR: the keystore decoded to nothing'
            exit 1
          fi
          echo 'keystore.properties: all four keys present, and its keystore decoded and is not empty'
        '''
        }
      }
    } catch (err) {
      if (credentialsPresent) {
        throw err
      }
      echo 'WARNING: no signing credentials on this controller -- the release APK will be debug-signed'
    }
  }

  // Deliberately before the Emulator stage: R8 is a separate code path from
  // assembleDebug and it is the one that produces the published artifact, so a
  // minification failure should cost seconds here rather than the 25 minutes the
  // matrix takes to find out.
  stage('Assemble release') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh "${gradleHome} ${gradleArgs} assembleRelease"
      }
    }
  }

  // Which key actually signed the artifact, in the log where it can still be
  // read months later. `apksigner verify` is a gate as much as a report: it exits
  // non-zero on an APK that does not verify, so a broken signature cannot reach
  // the Archive stage. The certificate fingerprint it prints is public
  // information -- nothing here is the secret.
  stage('Signing report') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh """
          set -euo pipefail
          "\${ANDROID_HOME}/build-tools/${buildTools}/apksigner" verify --verbose --print-certs \\
            app/build/outputs/apk/release/app-release.apk
        """
      }
    }
  }

  // Instrumented tests need a real Android runtime, which neither a plain JVM
  // unit test nor Robolectric can stand in for: the launch smoke test
  // inspects live widgets, and only an emulator exercises the game's own
  // threading and timing. So boot a headless AVD per API level and let Gradle
  // drive it.
  //
  // The google_apis image is a full Google APIs build, which is closer to what
  // a user actually runs than aosp_atd -- and it boots headless all the same
  // with -no-window. It is slower to boot and larger on disk than aosp_atd, so
  // the boot poll below is generous. Images and AVDs live in the toolcache, so
  // this is a no-op after the first run.
  //
  // Sequential, not parallel: the controller has 8 GB of RAM, and emulators
  // this size would thrash if run together.
  //
  // POSIX sh only -- the sh step runs /bin/sh, which is dash here, so no [[ ]].
  if (runTests) {
  stage('Unit tests') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh "${gradleHome} ${gradleArgs} testDebugUnitTest"
      }
    }
  }
  stage('Lint') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh "${gradleHome} ${gradleArgs} lintDebug"
      }
    }
  }
  stage('Emulator') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh """
          set -eu
          CACHE="\${JENKINS_HOME:-/var/lib/jenkins}/.toolcache/hotdeath"
          SDK="\$ANDROID_HOME"
          AVDHOME="\$CACHE/avd"
          ADB="\$SDK/platform-tools/adb"
          EMU="\$SDK/emulator/emulator"
          SDKMANAGER="\$SDK/cmdline-tools/latest/bin/sdkmanager"
          AVDMANAGER="\$SDK/cmdline-tools/latest/bin/avdmanager"
          export ANDROID_AVD_HOME="\$AVDHOME"
          mkdir -p "\$AVDHOME"

          # KVM lives in the kvm group, which the jenkins user is a member of.
          # Without it the x86_64 image falls back to software emulation and
          # never boots in reasonable time, so fail loudly instead.
          ACCEL="\$("\$EMU" -accel-check 2>&1 || true)"
          case "\$ACCEL" in
            *"installed and usable"*) ;;
            *)
              echo "KVM is unusable for the jenkins user" >&2
              echo "\$ACCEL" >&2
              exit 1
              ;;
          esac

          # A deterministic device set. connectedAndroidTest runs against
          # everything attached, so a stray emulator from an interactive
          # session would quietly join the matrix and skew the results.
          for serial in \$("\$ADB" devices 2>/dev/null | awk '\$1 ~ /^emulator-/ {print \$1}' || true); do
            echo "stopping stray emulator \$serial so the matrix is deterministic" >&2
            "\$ADB" -s "\$serial" emu kill > /dev/null 2>&1 || true
          done
          sleep 3

          # One --list pass, then match in the shell: a per-API --list would
          # re-fetch the remote repository four times over.
          AVAILABLE="\$("\$SDKMANAGER" --sdk_root="\$SDK" --list 2>/dev/null || true)"

          # API level : system-image tag, with an optional flavour third. The
          # tag is spelled out rather than derived from the API number because
          # Android 17 is published as 37.0/37.1/37.2, not a bare 37.
          #
          # API 37 is not in this list. Every published 37.x x86_64 image fails
          # on this controller: 37.0's google_apis build cannot commit a package
          # install at all, because the session commit needs the
          # persistent_data_block service (Block Disk Assurance, published by
          # vold) and that image does not publish it, so the installer dies
          # mid-commit with "Broken pipe (32)". 37.2's image never finishes
          # booting -- 176 system_server restarts and counting. Neither is an
          # app problem: 34-36 run the same APK and all 9 tests pass, and in the
          # runs where 37 got as far as collecting tests, all 6 PenaltyStackTest
          # cases passed. The 3 MainLaunchTest failures there targeted
          # com.smccloud.hotdeath.test, which AGP had already uninstalled, so
          # they are the same artifact issue #8 records as diagnosis 1 and not
          # evidence against 37. Issue #8 carries the traces and the untried
          # images.
          #
          # It cost roughly 30 minutes of boot polling per build to learn that,
          # so the level is out rather than left parked as known-failing. Add
          # it back as "37:37.2:google_apis_ps16k" once an image works.
          #
          # KNOWN_FAILING is kept because a future level may need it: those
          # levels still run -- they are how we find out when the app starts
          # working on a new platform -- but their results are archived rather
          # than published, so they do not fail the build.
          KNOWN_FAILING=''
          ran=''
          skipped=''
          failed=''
          known_failed=''
          for entry in 34:34 35:35 36:36; do
            api="\${entry%%:*}"
            rest="\${entry#*:}"
            tag="\${rest%%:*}"
            flavour="\${rest#*:}"
            # No third field means the ordinary google_apis image.
            case "\$flavour" in
              "\$tag") flavour=google_apis ;;
            esac
            IMAGE="system-images;android-\$tag;\$flavour;x86_64"
            # Every AVD is prefixed with this job's name, so two builds on the
            # same controller cannot collide on one AVD. The AVD directory is
            # shared state and the emulator is not -- a concurrent job booting
            # "api36" while this one is using it would fight over the same
            # config.ini and the same console port, and the loser fails in a way
            # that looks like a device problem rather than a name collision.
            #
            # It also means the archived result directories
            # (instrumented-results/<AVD> and failure-diagnostics/<AVD>) are
            # unambiguous about which job wrote them.
            #
            # One-time cost of the rename: the AVDs have to be recreated, since
            # avdmanager keys on the name. The system images themselves are
            # already in the toolcache and are not re-downloaded.
            #
            # The name is ALSO the cache key for the created AVD, so an image
            # swap has to change it. A bare "hot-death-uno-api37" from the 37.0
            # days would otherwise still be on disk, and reusing it would boot
            # the old image while reporting the new tag's results -- the worst
            # way to test an image swap. Only non-default images get the
            # qualified tag-flavour suffix, so 34-36 do not pay for a longer
            # name on top of it.
            AVD="hot-death-uno-api\$api"
            if [ "\$tag\$flavour" != "\${api}google_apis" ]; then
              AVD="hot-death-uno-api\$api-\$tag-\$flavour"
            fi

            enforced=yes
            for k in \$KNOWN_FAILING; do
              if [ "\$k" = "\$api" ]; then
                enforced=no
              fi
            done

            # Skip rather than fail when Google has not published this image
            # yet, so the matrix grows on its own as images appear.
            case "\$AVAILABLE" in
              *"\$IMAGE"*) ;;
              *)
                echo "SKIPPED \$AVD: \$IMAGE is not published yet" >&2
                skipped="\$skipped \$AVD"
                continue
                ;;
            esac

            if [ ! -d "\$SDK/system-images/android-\$tag/google_apis/x86_64" ]; then
              echo "installing \$IMAGE" >&2
              "\$SDKMANAGER" --sdk_root="\$SDK" "\$IMAGE" >&2
            fi
            if [ ! -f "\$AVDHOME/\$AVD.ini" ]; then
              echo "creating AVD \$AVD" >&2
              echo no | "\$AVDMANAGER" create avd -f -n "\$AVD" -k "\$IMAGE" >&2
            fi

            # Pin the data partition instead of trusting the image default. The
            # Android 17 image ships an 800 MB partition, which pm refuses to
            # install into: "Requested internal only, but not enough space".
            # The older images default to 6 GB, which is known to fit the app
            # and its test APK, so use that everywhere for a consistent matrix.
            # -wipe-data below rebuilds userdata, so the new size takes effect.
            DATA_PARTITION_BYTES=6442450944
            CFG="\$AVDHOME/\$AVD.avd/config.ini"
            if grep -q 'disk\\.dataPartition\\.size' "\$CFG"; then
              sed -i '/^[[:space:]]*disk\\.dataPartition\\.size[[:space:]]*=/d' "\$CFG"
              echo "enlarging data partition to \$DATA_PARTITION_BYTES bytes for \$AVD" >&2
            fi
            echo "disk.dataPartition.size=\$DATA_PARTITION_BYTES" >> "\$CFG"

            # -wipe-data keeps runs repeatable: the launch smoke test assumes
            # a fresh install with no saved game.
            #
            # Memory was briefly raised to 4 GB for api37 on the theory that it
            # was starving. It is not: the device log shows no lowmemorykiller,
            # no OOM and no FATAL, and the install fails at 4 GB exactly as it
            # does at 2 GB. The real cause is a missing system service, which
            # no amount of RAM supplies. See issue #8.
            "\$EMU" -avd "\$AVD" \\
              -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \\
              -gpu swiftshader_indirect -accel on -memory 2048 \\
              > "\$CACHE/emulator-\$AVD.log" 2>&1 &
            EMU_PID=\$!

            # Poll for boot rather than sleeping a fixed amount: the first boot
            # of a fresh AVD is much slower than a warm one.
            #
            # sys.boot_completed on its own is not enough. It is a sticky
            # property, so it stays 1 across a system_server restart -- and the
            # API 37 image does restart it (start_count 3 on a fresh boot).
            # Installing in that window fails with "device is still booting" or
            # "Can't find service: package". So also require the services the
            # test run needs to actually answer.
            #
            # And require the device to be "device" in adb's own listing, not
            # merely present. adb reports a booting emulator as "offline" while
            # it is still coming up, and Gradle skips offline devices without
            # failing, so a level gated only on the properties above can be
            # silently skipped and still counted as run.
            #
            # None of that is enough on its own. The API 37 image restarts
            # system_server during boot, and the gate above can pass against the
            # outgoing instance: the properties are sticky, and service check
            # answers for as long as the old instance is still bound. Proceeding
            # then means installing into a system_server that is about to die.
            # That is the "install-commit ... Broken pipe (32)" seen on api37,
            # and it surfaces as a flaky install rather than as a boot failure,
            # which is why it read as noise.
            #
            # So require the same answers twice, a gap apart, and require the
            # system_server PID to be identical in both. A restart changes it.
            #
            # The service checks are exact matches, not `grep -q found`. That
            # substring test also matches "not found", so it passed whether or
            # not the service was up, which made the check decorative. It
            # returns "Service package: found" or "Service package: not found".
            booted=0
            for _ in \$(seq 1 120); do
              if "\$ADB" devices | awk '\$1 ~ /^emulator-/ && \$2 == "device"' | grep -q . \\
                 && [ "\$("\$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r\\n')" = '1' ] \\
                 && "\$ADB" shell 'service check activity' 2>/dev/null | grep -qx 'Service activity: found' \\
                 && "\$ADB" shell 'service check package' 2>/dev/null | grep -qx 'Service package: found'; then

                # Same PID before and after the settle, and the services still
                # answering at the end. One round is not enough: a restart in
                # the gap is exactly what we are guarding against.
                pid_a="\$("\$ADB" shell pidof system_server 2>/dev/null | tr -d '\\r\\n')"
                sleep 10
                pid_b="\$("\$ADB" shell pidof system_server 2>/dev/null | tr -d '\\r\\n')"
                if [ -n "\$pid_a" ] && [ "\$pid_a" = "\$pid_b" ] \\
                   && "\$ADB" shell 'service check package' 2>/dev/null | grep -qx 'Service package: found'; then
                  booted=1
                  echo "\$AVD settled: system_server pid \$pid_a held for 10s" >&2
                  break
                fi
                echo "\$AVD still settling (system_server \$pid_a -> \$pid_b)" >&2
              fi
              sleep 3
            done
            if [ "\$booted" != '1' ]; then
              echo "\$AVD did not finish booting" >&2
              "\$ADB" devices >&2 || true
              tail -c 2000 "\$CACHE/emulator-\$AVD.log" >&2 || true
              # A level that never settles is the likeliest thing to need
              # reading later, and this is the only place its device log
              # still exists -- the run is ending, and the next boot wipes
              # it. Goes to stderr so it lands in the console log, which
              # survives a failed build; the archived report never gets
              # written when we exit here.
              echo "--- \$AVD: crashes and low-memory kills ---" >&2
              "\$ADB" logcat -d -v brief 2>&1 \\
                | grep -iE 'FATAL|AndroidRuntime|watchdog|lowmemorykiller|kswapd|Out of memory|oom|system_server.*(died|kill)|SystemServer' \\
                | tail -60 >&2 || true
              echo "--- \$AVD: end device log ---" >&2
              # An enforced level that cannot boot must still fail the build.
              # A known-failing one has already said it is not gating, and
              # failing here costs us the other three levels' results -- a
              # device that never settles is a known condition on api37, not
              # a reason to stop reporting 34-36.
              if [ "\$enforced" = 'yes' ]; then
                exit 1
              fi
              echo "\$AVD never settled; recording as known-failing and moving on" >&2
              known_failed="\$known_failed \$AVD"
              continue
            fi
            echo "\$AVD booted" >&2

            # Before the test run, on a known-failing level, ask the device
            # whether this platform can launch the app at all. Two reasons this
            # has to happen here rather than after the test run:
            # AGP uninstalls both APKs when the run finishes, so a probe
            # afterwards reports "activity does not exist" for a package that
            # was installed moments earlier -- which reads exactly like a
            # platform problem and is nothing of the kind. And installing the
            # app ourselves makes the answer independent of how the test task
            # happens to package and push it.
            if [ "\$enforced" = 'no' ]; then
              DIAG="app/build/failure-diagnostics/\$AVD"
              mkdir -p "\$DIAG"
              APP_APK="app/build/outputs/apk/debug/app-debug.apk"
              {
                echo "=== \$AVD (API \$api): can this platform launch the app? ==="
                echo
                echo "--- install the app ---"
                "\$ADB" install -r -t "\$APP_APK" 2>&1
                echo
                echo "--- installed? ---"
                "\$ADB" shell pm path com.smccloud.hotdeath 2>&1
                echo
                echo "--- registered launcher entries for this package ---"
                "\$ADB" shell cmd package query-activities \\
                  -a android.intent.action.MAIN \\
                  -c android.intent.category.LAUNCHER \\
                  com.smccloud.hotdeath 2>&1 | head -20
                echo
                echo "--- can the platform resolve the launcher entry? ---"
                "\$ADB" shell cmd package resolve-activity \\
                  --brief -a android.intent.action.MAIN \\
                  -c android.intent.category.LAUNCHER \\
                  com.smccloud.hotdeath 2>&1
                echo
                echo "--- am start, by explicit component ---"
                "\$ADB" shell am start -W -n com.smccloud.hotdeath/.Main 2>&1
                echo
                echo "--- platform refusals ---"
                # Exceptions, not just their call frames. A grep for the service
                # names catches the tail of a stack trace but drops the
                # exception and message above it, which is the part that says
                # why the install was refused.
                "\$ADB" logcat -d -v brief 2>&1 \\
                  | grep -iE 'ActivityManager|ActivityTaskManager|PackageManager|SystemServiceRegistry|hotdeath' \\
                  | tail -80
                echo
                echo "--- crashes and low-memory kills ---"
                "\$ADB" logcat -d -v brief 2>&1 \\
                  | grep -iE 'FATAL|AndroidRuntime|watchdog|lowmemorykiller|kswapd|Out of memory|oom' \\
                  | tail -40
              } > "\$DIAG/report.txt" 2>&1 || true
              echo "\$AVD: wrote \$DIAG/report.txt" >&2
            fi

            # Run every API even if one fails, so a single build reports the
            # whole matrix instead of stopping at the first bad device. The
            # exit code is re-raised at the end.
            if "${gradleHome}" ${gradleArgs} connectedDebugAndroidTest; then
              ran="\$ran \$AVD"
            else
              ran="\$ran \$AVD"
              if [ "\$enforced" = 'yes' ]; then
                echo "TESTS FAILED on \$AVD" >&2
                failed="\$failed \$AVD"
              else
                echo "TESTS FAILED on \$AVD, but \$AVD is a known-failing level" >&2
                echo "(a level that cannot launch the app's activities -- see issue #8)" >&2
                echo "recording it, and NOT failing the build" >&2
                known_failed="\$known_failed \$AVD"
              fi
            fi

            # Keep this level's XML. AGP empties
            # outputs/androidTest-results/connected/debug on every run, so with a
            # sequential matrix each level would otherwise overwrite the last and
            # only the final API would ever be published. Known-failing levels go
            # to a separate tree: the junit step fails the build on failing tests,
            # so their results are archived as artifacts instead of published.
            if [ "\$enforced" = 'yes' ]; then
              KEEP="app/build/instrumented-results/\$AVD"
            else
              KEEP="app/build/instrumented-results-known-failing/\$AVD"
            fi
            mkdir -p "\$KEEP"
            for f in app/build/outputs/androidTest-results/connected/debug/TEST-*.xml; do
              if [ -f "\$f" ]; then
                cp "\$f" "\$KEEP/"
              fi
            done
            kept=\$(find "\$KEEP" -name '*.xml' | wc -l)
            echo "\$AVD: kept \$kept result file(s) in \$KEEP" >&2

            # Stop it before the next API, or the next emulator cannot claim
            # the console port.
            #
            # `adb emu kill` alone is not enough. It asks the emulator to shut
            # down, and the emulator can decline or take longer than the wait
            # allows; one run left api36 listed as "device" for the whole 60s
            # window, so the next level would have booted alongside it. So
            # escalate rather than hope: kill the console, then the process,
            # then force it, and only believe the list once it is empty.
"\$ADB" emu kill > /dev/null 2>&1 || true
          kill "\$EMU_PID" > /dev/null 2>&1 || true
            kill "\$EMU_PID" > /dev/null 2>&1 || true
            torn_down=0
            attempt=0
            while [ "\$attempt" -lt 30 ]; do
              attempt=\$((attempt + 1))
              if [ -z "\$("\$ADB" devices | awk '\$1 ~ /^emulator-/' || true)" ]; then
                torn_down=1
                break
              fi
              sleep 2
              # Past the halfway point, stop asking nicely.
              if [ "\$attempt" = 15 ]; then
                "\$ADB" kill-server > /dev/null 2>&1 || true
                "\$ADB" start-server > /dev/null 2>&1 || true
                kill -9 "\$EMU_PID" > /dev/null 2>&1 || true
                # Anything still holding emulator-* after our kill is not ours.
                for pid in \$(pgrep -f 'qemu-system' || true); do
                  kill -9 "\$pid" > /dev/null 2>&1 || true
                done
              fi
            done

            # Do not fall through on a failed teardown. A surviving emulator
            # stays in adb's device list, and connectedAndroidTest runs against
            # everything attached, so the next level would boot a second AVD
            # alongside it and Gradle would run that level's suite against the
            # OLD device. api37 was reported green on a run where it never
            # executed a test: the stale api36 emulator was still attached, the
            # new api37 came up offline and was skipped, and the suite ran
            # against api36 instead. That is a false pass on a level the build
            # is supposed to be gating, so fail here rather than report it.
            if [ "\$torn_down" != '1' ]; then
              echo "\$AVD did not shut down; refusing to run the next level" >&2
              "\$ADB" devices >&2 || true
              exit 1
            fi
          done

          echo "emulator matrix -- tested:[\$ran] skipped:[\$skipped] failed:[\$failed] known-failing:[\$known_failed]"
          if [ -z "\$ran" ]; then
            echo "no AVD in the matrix could be started" >&2
            exit 1
          fi
          if [ -n "\$failed" ]; then
            echo "instrumented tests failed on: \$failed" >&2
            exit 1
          fi
        """
      }
    }
  }
  stage('Publish reports') {
    // instrumented-results is the per-API copy the Emulator stage keeps, because
    // AGP empties outputs/androidTest-results/connected/debug on every run and a
    // sequential matrix would otherwise publish only the last API. Failing tests
    // here fail the build, which is exactly what we want for the enforced levels.
    junit allowEmptyResults: true, testResults:
      "${moduleDir}/app/build/test-results/**/*.xml," +
      "${moduleDir}/app/build/instrumented-results/**/*.xml"
    archiveArtifacts artifacts: "${moduleDir}/app/build/reports/lint-results-debug.html",
                     allowEmptyArchive: true, fingerprint: true
    // Known-failing levels (see KNOWN_FAILING) are archived rather than
    // published, so the report is still retrievable without failing the build.
    archiveArtifacts artifacts: "${moduleDir}/app/build/instrumented-results-known-failing/**/*.xml",
                     allowEmptyArchive: true, fingerprint: true
    // What the device said about itself when a level failed. Archived, not
    // printed: this is several screens of package-manager output that is only
    // interesting when something has already gone wrong.
    archiveArtifacts artifacts: "${moduleDir}/app/build/failure-diagnostics/**/report.txt",
                     allowEmptyArchive: true, fingerprint: true
  }
  }

  // README images. Off by default and not a gate -- see the SCREENSHOTS
  // parameter for why. It installs the *release* APK rather than the debug one,
  // because these are pictures of what people actually download, and it drives
  // the UI by resource id rather than by coordinates, so it keeps working when
  // a layout moves.
  //
  // Navigation is the awkward part, and it is awkward because the game is
  // genuinely random: the dealer is picked at random, so the "how many cards do
  // you want to deal?" dialog only appears when a human happens to deal. And the
  // options menu -- which is the only route to the card catalog -- is only
  // visible on the human's turn. Both are handled by waiting for the view we
  // need to appear rather than by assuming a fixed sequence, and the screenshot
  // is taken of whatever state the app is genuinely in.
  if (screenshots) {
  stage('Screenshots') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh """
          set -eu
          CACHE="\${JENKINS_HOME:-/var/lib/jenkins}/.toolcache/hotdeath"
          SDK="\$ANDROID_HOME"
          AVDHOME="\$CACHE/avd"
          ADB="\$SDK/platform-tools/adb"
          EMU="\$SDK/emulator/emulator"
          export ANDROID_AVD_HOME="\$AVDHOME"
          OUT=app/build/screenshots
          APK=app/build/outputs/apk/release/app-release.apk
          PKG=com.smccloud.hotdeath
          # api35, not the first level in the matrix: the image is the one that
          # has booted reliably most often here, and if this ever runs
          # concurrently with the Emulator stage it will not collide either.
          AVD=hot-death-uno-api35
          mkdir -p "\$OUT"
          rm -f "\$OUT"/*.png

          ACCEL="\$("\$EMU" -accel-check 2>&1 || true)"
          case "\$ACCEL" in
            *"installed and usable"*) ;;
            *) echo "KVM is unusable for the jenkins user" >&2; echo "\$ACCEL" >&2; exit 1 ;;
          esac

          "\$EMU" -avd "\$AVD" \\
            -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \\
            -gpu swiftshader_indirect -accel on -memory 2048 \\
            > "\$CACHE/emulator-\$AVD.log" 2>&1 &
          EMU_PID=\$!

          # The same settle gate the Emulator stage uses: device state, boot
          # completed, both services answering, and a system_server PID that
          # holds for 10s. Shortened to one round, because a failure here is
          # reported loudly rather than retried across a matrix.
          booted=0
          for _ in \$(seq 1 120); do
            if "\$ADB" devices | awk '\$1 ~ /^emulator-/ && \$2 == "device"' | grep -q . \\
               && [ "\$("\$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r\\n')" = '1' ] \\
               && "\$ADB" shell 'service check activity' 2>/dev/null | grep -qx 'Service activity: found' \\
               && "\$ADB" shell 'service check package' 2>/dev/null | grep -qx 'Service package: found'; then
              pid_a="\$("\$ADB" shell pidof system_server 2>/dev/null | tr -d '\\r\\n')"
              sleep 10
              pid_b="\$("\$ADB" shell pidof system_server 2>/dev/null | tr -d '\\r\\n')"
              if [ -n "\$pid_a" ] && [ "\$pid_a" = "\$pid_b" ]; then
                booted=1
                echo "\$AVD settled: system_server pid \$pid_a held for 10s" >&2
                break
              fi
            fi
            sleep 3
          done
          if [ "\$booted" != '1' ]; then
            echo "ERROR: \$AVD did not boot; screenshots cannot be taken" >&2
            tail -c 2000 "\$CACHE/emulator-\$AVD.log" >&2 || true
            exit 1
          fi

          # --- capture resolution --------------------------------------------
          # The CI AVDs are 320x640, which is right for running tests and wrong
          # for a README: a 320px image in a browser is a postage stamp, and this
          # game's whole appeal is how the cards look.
          #
          # wm size overrides the display for this session only and the AVD on
          # disk is untouched, so nothing the emulator matrix depends on moves.
          # 1080x2160 at xhdpi (320) is an ordinary phone geometry, so the layout
          # is the one a real device would show rather than an upscaled version
          # of the CI one.
          #
          # Best effort throughout. If the override is refused the shots are just
          # the AVD's native size, which is smaller but still correct, and failing
          # the whole documentation stage over it would be the wrong trade.
          "\$ADB" shell wm size 1080x2160 > /dev/null 2>&1 || true
          "\$ADB" shell wm density 320 > /dev/null 2>&1 || true
          sleep 4
          echo "  display now \$("\$ADB" shell wm size 2>/dev/null | tr -d '\\r\\n')" >&2

          # --- helpers -------------------------------------------------------
          # uiautomator dump is how the script finds a view. It only reports
          # nodes that are currently visible and laid out, which is exactly the
          # property the game needs: the options menu is INVISIBLE until the
          # human's turn, so polling for it is how we know the turn arrived
          # without hard-coding a wait.
          #
          # uiautomator writes quoted attributes -- resource-id=".../btn_menu_help"
          # -- so every pattern below needs a literal double quote in it. Writing
          # that quote with a shell backslash in front of it does not work, and did
          # not: Groovy consumes the backslash and hands the shell
          # resource-id=".../btn_menu_help" with the quotes no longer escaped. That
          # is unbalanced quoting, and dash does not complain about it where it is
          # -- build #97 died on a "(" eighty lines later that was never the
          # problem. Q carries the character instead, so no escaped quote crosses
          # the Groovy boundary at all.
          Q='"'
          ui_dump () {
            "\$ADB" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
            "\$ADB" shell cat /sdcard/ui.xml 2>/dev/null | sed 's/></>\\n</g'
          }
          node_bounds () {  # node_bounds <grep-pattern>
            # awk rather than sed, and the program is single-quoted, so the double
            # quotes in the regex are literal to the shell and only the \\[] need
            # to survive Groovy.
            ui_dump | grep -m1 "\$1" | awk '
              match(\$0, /bounds="\\[[0-9]+,[0-9]+\\]\\[[0-9]+,[0-9]+\\]"/) {
                b = substr(\$0, RSTART, RLENGTH)
                gsub(/[^0-9]+/, " ", b)
                # The bracket and comma runs become spaces, which leaves one at
                # each end. Harmless for set --, but trim so the value is exactly
                # "x1 y1 x2 y2" and nothing downstream has to know that.
                sub(/^ +/, "", b)
                sub(/ +\$/, "", b)
                print b
              }'
          }
          tap_id () {  # tap_id <resource-id suffix>
            local label="\$1" b
            b=\$(node_bounds "resource-id=\${Q}[^\${Q}]*id/\${label}")
            if [ -z "\$b" ]; then echo "  WARN: no visible view with id/\$label" >&2; return 1; fi
            set -- \$b
            # \$label, not \$1: the set -- above has already reused \$1 for the
            # first coordinate, so echoing \$1 here printed the x offset and not
            # the view that was tapped.
            echo "  tap \$label at \$(( (\$1 + \$3) / 2 )),\$(( (\$2 + \$4) / 2 ))" >&2
            "\$ADB" shell input tap \$(( (\$1 + \$3) / 2 )) \$(( (\$2 + \$4) / 2 ))
          }
          tap_text () {  # tap_text <exact text>
            local label="\$1" b
            b=\$(node_bounds "text=\${Q}\${label}\${Q}")
            if [ -z "\$b" ]; then echo "  (no visible node with text '\$label' -- skipping)" >&2; return 0; fi
            set -- \$b
            echo "  tap text '\$label' at \$(( (\$1 + \$3) / 2 )),\$(( (\$2 + \$4) / 2 ))" >&2
            "\$ADB" shell input tap \$(( (\$1 + \$3) / 2 )) \$(( (\$2 + \$4) / 2 ))
          }
          tap_first () {  # tap_first <grep-pattern>
            # Taps the first node matching a pattern rather than the centre of a
            # container, because those are not the same point. A GridView's
            # gutters between columns are touch-transparent, so a tap at the
            # container's centre can land in one and reach the container instead
            # of a cell: no item click, no dialog, and two byte-identical
            # screenshots. That is not hypothetical. The gridview spans
            # [125,68][955,1842], so its centre was x=540, and the columns end at
            # 521 and resume at 558 -- 540 sits in that 36px gutter.
            #
            # The catalog's cells are ImageViews with no resource-id, which is
            # what makes this the only way to reach one.
            local pattern="\$1" b
            b=\$(node_bounds "\$pattern")
            if [ -z "\$b" ]; then echo "  WARN: no node matching \$pattern" >&2; return 1; fi
            set -- \$b
            echo "  tap \$pattern at \$(( (\$1 + \$3) / 2 )),\$(( (\$2 + \$4) / 2 ))" >&2
            "\$ADB" shell input tap \$(( (\$1 + \$3) / 2 )) \$(( (\$2 + \$4) / 2 ))
          }
          wait_id () {  # wait_id <resource-id suffix> <tries>
            local i=0 b
            while [ "\$i" -lt "\$2" ]; do
              b=\$(node_bounds "resource-id=\${Q}[^\${Q}]*id/\${1}")
              if [ -n "\$b" ]; then return 0; fi
              i=\$((i+1)); sleep 2
            done
            echo "  WARN: gave up waiting for id/\$1" >&2
            return 1
          }
          shot () {  # shot <name> [dump]
            # An optional second argument writes the uiautomator dump alongside
            # the PNG. When a screenshot comes out wrong the PNG alone cannot say
            # why -- two of them were byte-identical and nothing in the log said
            # what was on screen. The dump is what turns that into a cause.
            if [ -n "\${2:-}" ]; then
              ui_dump > "\$OUT/\$1.xml"
            fi
            "\$ADB" exec-out screencap -p > "\$OUT/\$1.png"
            echo "  captured \$1.png (\$(wc -c < "\$OUT/\$1.png") bytes)" >&2
          }

          # --- capture -------------------------------------------------------
          # Release APK, not debug: these are pictures of the shipped build, and
          # using the minified artifact is also a second confirmation that R8
          # did not take anything out of the UI.
          "\$ADB" install -r -t "\$APK"
          "\$ADB" shell am start -W -n "\$PKG/.Main" >&2
          sleep 5
          shot 01-main

          tap_id btn_new_game
          sleep 3
          # Only present when the dealer is human, which is a coin flip. Best
          # effort by design: a miss is not an error.
          tap_text 7
          sleep 2

          # The options menu becomes visible on the human's turn. Waiting for it
          # is how we know we are there, and it doubles as settling the board.
          wait_id btn_menu_help 15
          sleep 2
          shot 02-table

          tap_id btn_menu_help
          sleep 4
          shot 03-card-catalog dump

          # Tapping a cell in the grid sets the help card and opens the dialog, per
          # GameActivity.showCardCatalog's item click. The cell, not the gridview:
          # the gridview's centre falls in a gutter between columns.
          tap_first 'class="android\\.widget\\.ImageView"'
          sleep 3
          shot 04-card-help dump

          # The dialog opening is the whole point of the shot, so verify it rather
          # than trust it. Two of these screenshots were once byte-identical and
          # nothing in the log said why; a distinct hash and a visible view are
          # the difference between evidence and hope.
          if cmp -s "\$OUT/03-card-catalog.png" "\$OUT/04-card-help.png"; then
            echo "ERROR: 04-card-help.png is identical to 03-card-catalog.png," >&2
            echo "       so the cell tap did not open the help dialog." >&2
            exit 1
          fi
          if ! grep -q 'id/text' "\$OUT/04-card-help.xml"; then
            echo "ERROR: the help dialog text view is not on screen in 04" >&2
            exit 1
          fi

          ls -l "\$OUT"
          COUNT=\$(ls -1 "\$OUT"/*.png | wc -l)
          echo "captured \$COUNT screenshot(s)"
          if [ "\$COUNT" -lt 3 ]; then
            echo "ERROR: only \$COUNT of the screenshots were captured" >&2
            exit 1
          fi

          # Put the display back before the emulator goes away. wm size writes to the
          # running system and can be picked up by a saved snapshot, and these
          # AVDs are shared with the API 34-36 instrumented matrix -- a 1080x2160
          # display leaking into those runs would be a failure a long way from
          # here. The reset is best effort for the same reason the override is:
          # the emulator is about to be killed either way.
          "\$ADB" shell wm size reset > /dev/null 2>&1 || true
          "\$ADB" shell wm density reset > /dev/null 2>&1 || true

          "\$ADB" emu kill > /dev/null 2>&1 || true
          torn_down=0
          attempt=0
          while [ "\$attempt" -lt 30 ]; do
            attempt=\$((attempt + 1))
            if [ -z "\$("\$ADB" devices | awk '\$1 ~ /^emulator-/' || true)" ]; then
              torn_down=1; break
            fi
            sleep 2
            if [ "\$attempt" = 15 ]; then
              "\$ADB" kill-server > /dev/null 2>&1 || true
              "\$ADB" start-server > /dev/null 2>&1 || true
              kill -9 "\$EMU_PID" > /dev/null 2>&1 || true
              for pid in \$(pgrep -f 'qemu-system' || true); do
                kill -9 "\$pid" > /dev/null 2>&1 || true
              done
            fi
          done
          if [ "\$torn_down" != '1' ]; then
            echo "\$AVD did not shut down; refusing to continue" >&2
            "\$ADB" devices >&2 || true
            exit 1
          fi
        """
      }
    }
    archiveArtifacts artifacts: "${moduleDir}/app/build/screenshots/*.png",
allowEmptyArchive: false, fingerprint: true
    // The uiautomator dumps travel with the PNGs. They are only interesting
    // when a screenshot is wrong, and they are the only record of what was
    // actually on screen when it was wrong. Groovy accepts a # comment only in
    // column 0, so this is // like every other comment in the file.
    archiveArtifacts artifacts: "${moduleDir}/app/build/screenshots/*.xml",
                     allowEmptyArchive: true, fingerprint: true
  }
  }

  stage('Archive') {
    archiveArtifacts artifacts: "${moduleDir}/app/build/outputs/apk/debug/*.apk",
                     allowEmptyArchive: true, fingerprint: true
    // The release APK is the artifact that gets published, so it is archived
    // with a fingerprint like the rest: a published binary has to be traceable
    // back to the build that produced it.
    archiveArtifacts artifacts: "${moduleDir}/app/build/outputs/apk/release/*.apk",
                     allowEmptyArchive: true, fingerprint: true
    // The APK check has to run in sh. file(...) hands back an
    // UninstantiatedDescribableWithInterpolation inside the script-security
    // sandbox, so .listFiles() there dies with MissingMethodException.
    // `|| true` keeps set -e from tripping when the glob matches nothing.
    def apks = sh(
      script: """
        set -euo pipefail
        ls -1 '${moduleDir}/app/build/outputs/apk/debug/'*.apk 2>/dev/null || true
        ls -1 '${moduleDir}/app/build/outputs/apk/release/'*.apk 2>/dev/null || true
      """,
      returnStdout: true
    ).trim()
    if (apks.isEmpty()) {
      echo 'WARNING: no APK produced, yet the build reported success'
      currentBuild.result = 'UNSTABLE'
    } else {
      echo "APK(s) produced:\n${apks}"
    }

    // Separate from the check above on purpose: a build with a debug APK but no
    // release APK is publishable-looking but has nothing to publish, and the
    // warning above would let it read as a normal success.
    def releaseApks = sh(
      script: """
        set -euo pipefail
        ls -1 '${moduleDir}/app/build/outputs/apk/release/'*.apk 2>/dev/null || true
      """,
      returnStdout: true
    ).trim()
    if (releaseApks.isEmpty()) {
      echo 'WARNING: no release APK, so there is no artifact to publish'
      currentBuild.result = 'UNSTABLE'
    } else {
      echo "Publishable artifact(s):\n${releaseApks}"
    }
  }

  // Destroy the signing material, which is a plaintext keystore password in the
  // workspace. This runs on the way out of a successful pipeline only -- a
  // pipeline-level `post` is not valid inside `node`, and wrapping every stage
  // in try/finally to get the same guarantee is not worth reindenting 600 lines
  // over. The Signing material stage pre-cleans before it writes, so the
  // residual risk is a failed build leaving the file on disk until the next run,
  // never a stale key being used.
  stage('Remove signing material') {
    removeSigningMaterial()
  }
}
