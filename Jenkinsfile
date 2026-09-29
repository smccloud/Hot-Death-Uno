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

  stage('Publish reports') {
    junit allowEmptyResults: true, testResults: "${moduleDir}/app/build/test-results/**/*.xml"
    archiveArtifacts artifacts: "${moduleDir}/app/build/reports/lint-results-debug.html",
                     allowEmptyArchive: true, fingerprint: true
  }

  stage('Archive') {
    archiveArtifacts artifacts: "${moduleDir}/app/build/outputs/apk/debug/*.apk",
                     allowEmptyArchive: true, fingerprint: true
    def apks = file("${moduleDir}/app/build/outputs/apk/debug").listFiles()
    if (apks == null || apks.findAll { it.name.endsWith('.apk') }.isEmpty()) {
      echo 'WARNING: no APK produced, yet the build reported success'
      currentBuild.result = 'UNSTABLE'
    }
  }
}
