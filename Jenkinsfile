// Hot Death Uno - CI
// Scripted syntax on purpose: this Jenkins lacks pipeline-model-definition,
// so `pipeline { agent any }` will not parse. Controller is Linux, so `sh`.
// Only these plugins are assumed, all verified present on the instance:
//   workflow-cps, workflow-basic-steps, workflow-durable-task-step,
//   workflow-scm-step, workflow-support

def MODULE = 'com.runtsoft.hotdeath'
def GRADLE_VERSION = '8.13'
def ANDROID_API = '36'
def ANDROID_BUILD_TOOLS = '36.0.0'
def CACHE = "${env.JENKINS_HOME ?: '/var/jenkins_home'}/.toolcache/hotdeath"

// The repo ships no gradle-wrapper.jar, so ./gradlew exits 0 without building
// anything. That silent-success trap is why the wrapper is not used here.
def resolveGradle() {
  if (file("${MODULE}/gradle/wrapper/gradle-wrapper.jar").exists()) {
    return "./gradlew"
  }
  def dist = "${CACHE}/gradle-${GRADLE_VERSION}"
  if (!file("${dist}/bin/gradle").exists()) {
    sh """
      set -euo pipefail
      mkdir -p '${CACHE}'
      curl -fsSL -o '${dist}.zip' \
        'https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip'
      curl -fsSL -o '${dist}.zip.sha256' \
        'https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip.sha256'
      cd '${CACHE}' && sha256sum -c '${dist}.zip.sha256'
      unzip -q -o '${dist}.zip'
    """
  }
  return "${dist}/bin/gradle"
}

// AGP reads ANDROID_HOME, so local.properties is unnecessary -- which is what
// we want, since it is gitignored and holds a machine-specific SDK path.
def resolveAndroidSdk() {
  def found = [env.ANDROID_HOME, env.ANDROID_SDK_ROOT, "${CACHE}/android-sdk"]
    .findAll { it }
    .find { file("${it}/cmdline-tools").exists() || file("${it}/tools").exists() }
  if (found) {
    return found
  }
  def sdk = "${CACHE}/android-sdk"
  sh """
    set -euo pipefail
    mkdir -p '${sdk}/cmdline-tools'
    curl -fsSL -o /tmp/cmdtools.zip \
      'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip'
    unzip -q -o /tmp/cmdtools.zip -d /tmp/cmdtools
    rm -rf '${sdk}/cmdline-tools/latest'
    mv /tmp/cmdtools/cmdline-tools '${sdk}/cmdline-tools/latest'
    yes | '${sdk}/cmdline-tools/latest/bin/sdkmanager' --licenses > /dev/null
    '${sdk}/cmdline-tools/latest/bin/sdkmanager' \
      'platform-tools' 'platforms;android-${ANDROID_API}' 'build-tools;${ANDROID_BUILD_TOOLS}'
  """
  return sdk
}

node {
  def gradleCmd
  def sdkHome

  // Every Gradle invocation goes through here so ANDROID_HOME is never missed.
  def gradleIn = { String task ->
    dir(MODULE) {
      withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
        sh "${gradleCmd} --no-daemon --stacktrace ${task}"
      }
    }
  }

  stage('Checkout') {
    checkout scm
  }

  stage('Toolchain') {
    sh 'java -version 2>&1 | head -n 3'
    if (env.JAVA_HOME) {
      echo "JAVA_HOME=${env.JAVA_HOME}"
    } else {
      echo 'WARNING: JAVA_HOME unset; build may fail. AGP 8.11 requires JDK 17+.'
    }
  }

  stage('Android SDK') {
    sdkHome = resolveAndroidSdk()
    sh """
      set -eu
      test -d '${sdkHome}/platforms/android-${ANDROID_API}' || { echo 'MISSING platform android-${ANDROID_API}'; exit 1; }
      test -d '${sdkHome}/build-tools/${ANDROID_BUILD_TOOLS}' || { echo 'MISSING build-tools ${ANDROID_BUILD_TOOLS}'; exit 1; }
      echo "SDK verified: \$(ls '${sdkHome}/platforms')"
    """
  }

  stage('Gradle') {
    gradleCmd = resolveGradle()
    sh "${gradleCmd} --version | sed -n '1,8p'"
  }

  stage('Unit tests') {
    gradleIn('testDebugUnitTest')
  }

  stage('Lint') {
    gradleIn('lintDebug')
  }

  stage('Assemble') {
    gradleIn('assembleDebug')
  }

  stage('Publish reports') {
    junit allowEmptyResults: true, testResults: "${MODULE}/app/build/test-results/**/*.xml"
    archiveArtifacts artifacts: "${MODULE}/app/build/reports/lint-results-debug.html",
                     allowEmptyArchive: true, fingerprint: true
  }

  stage('Archive') {
    def apks = file("${MODULE}/app/build/outputs/apk/debug").listFiles()
    if (apks == null || apks.findAll { it.name.endsWith('.apk') }.isEmpty()) {
      echo 'WARNING: no APK produced, yet the build reported success'
      currentBuild.result = 'UNSTABLE'
    } else {
      archiveArtifacts artifacts: "${MODULE}/app/build/outputs/apk/debug/*.apk", fingerprint: true
    }
  }
}
