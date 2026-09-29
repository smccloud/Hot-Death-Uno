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
              strategy: [$class: 'LogRotator', numToKeepStr: '10']]])

node {
  def moduleDir = 'com.smccloud.hotdeath'
  def gradleVersion = '8.13'
  def api = '36'
  // AGP 8.11's *default* build-tools revision. This project never sets
  // buildToolsVersion, so AGP resolves 35.0.0 no matter how new the installed
  // revision is.
  def buildTools = '35.0.0'
  def sdkHome
  def gradleHome

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
  stage('Unit tests') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh "${gradleHome} --no-daemon --stacktrace testDebugUnitTest"
      }
    }
  }

  stage('Lint') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh "${gradleHome} --no-daemon --stacktrace lintDebug"
      }
    }
  }

  stage('Assemble') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh "${gradleHome} --no-daemon --stacktrace assembleDebug"
      }
    }
  }

  // Instrumented tests need a real Android runtime, which the JVM unit tests
  // cannot stand in for: org.json is a stub there, and the launch smoke test
  // needs a real Activity. So boot a headless AVD per API level and let Gradle
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
  stage('Emulator') {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      dir(moduleDir) {
        sh """
          set -euo pipefail
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
          # never boots in reasonable time, so fail loudly instead. Matching in
          # bash rather than piping to grep: under `set -o pipefail` a `grep -q`
          # that exits on first match can SIGPIPE its producer and fail the
          # whole pipeline.
          ACCEL="\$("\$EMU" -accel-check 2>&1 || true)"
          if [[ "\$ACCEL" != *"installed and usable"* ]]; then
            echo "KVM is unusable for the jenkins user" >&2
            echo "\$ACCEL" >&2
            exit 1
          fi

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

          ran=''
          skipped=''
          for api in 34 35 36 37; do
            IMAGE="system-images;android-\$api;google_apis;x86_64"
            AVD="api\$api"

            # api37 is listed for when Google publishes an image for it; until
            # then it is skipped loudly rather than silently dropped.
            if [[ "\$AVAILABLE" != *"\$IMAGE"* ]]; then
              echo "SKIPPED \$AVD: \$IMAGE is not published yet" >&2
              skipped="\$skipped \$AVD"
              continue
            fi

            if [ ! -d "\$SDK/system-images/android-\$api/google_apis/x86_64" ]; then
              echo "installing \$IMAGE" >&2
              "\$SDKMANAGER" --sdk_root="\$SDK" "\$IMAGE" >&2
            fi
            if [ ! -f "\$AVDHOME/\$AVD.ini" ]; then
              echo "creating AVD \$AVD" >&2
              echo no | "\$AVDMANAGER" create avd -f -n "\$AVD" -k "\$IMAGE" >&2
            fi

            # -wipe-data keeps runs repeatable: the launch smoke test assumes
            # a fresh install with no saved game.
            "\$EMU" -avd "\$AVD" \\
              -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \\
              -gpu swiftshader_indirect -accel on -memory 2048 \\
              > "\$CACHE/emulator-\$AVD.log" 2>&1 &
            EMU_PID=\$!

            # Poll for boot rather than sleeping a fixed amount: the first boot
            # of a fresh AVD is much slower than a warm one.
            booted=0
            for _ in \$(seq 1 120); do
              if [ "\$("\$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r\\n')" = '1' ]; then
                booted=1
                break
              fi
              sleep 3
            done
            if [ "\$booted" != '1' ]; then
              echo "\$AVD did not finish booting" >&2
              "\$ADB" devices >&2 || true
              tail -c 2000 "\$CACHE/emulator-\$AVD.log" >&2 || true
              exit 1
            fi
            echo "\$AVD booted" >&2

            "${gradleHome}" --no-daemon --stacktrace connectedDebugAndroidTest

            ran="\$ran \$AVD"

            # Stop it before the next API, or the next emulator cannot claim
            # the console port.
            "\$ADB" emu kill > /dev/null 2>&1 || true
            kill "\$EMU_PID" > /dev/null 2>&1 || true
            for _ in \$(seq 1 30); do
              if [ -z "\$("\$ADB" devices | awk '\$1 ~ /^emulator-/' || true)" ]; then
                break
              fi
              sleep 2
            done
          done

          echo "emulator matrix -- tested:[\$ran] skipped:[\$skipped]"
          if [ -z "\$ran" ]; then
            echo "no AVD in the matrix could be started" >&2
            exit 1
          fi
        """
      }
    }
  }

  stage('Publish reports') {
    junit allowEmptyResults: true, testResults: [
      "${moduleDir}/app/build/test-results/**/*.xml",
      "${moduleDir}/app/build/outputs/androidTest-results/connected/**/*.xml"
    ]
    archiveArtifacts artifacts: "${moduleDir}/app/build/reports/lint-results-debug.html",
                     allowEmptyArchive: true, fingerprint: true
  }

  stage('Archive') {
    archiveArtifacts artifacts: "${moduleDir}/app/build/outputs/apk/debug/*.apk",
                     allowEmptyArchive: true, fingerprint: true
    // The APK check has to run in sh. file(...) hands back an
    // UninstantiatedDescribableWithInterpolation inside the script-security
    // sandbox, so .listFiles() there dies with MissingMethodException.
    // `|| true` keeps set -e from tripping when the glob matches nothing.
    def apks = sh(
      script: """
        set -euo pipefail
        ls -1 '${moduleDir}/app/build/outputs/apk/debug/'*.apk 2>/dev/null || true
      """,
      returnStdout: true
    ).trim()
    if (apks.isEmpty()) {
      echo 'WARNING: no APK produced, yet the build reported success'
      currentBuild.result = 'UNSTABLE'
    } else {
      echo "APK(s) produced:\n${apks}"
    }
  }
}
