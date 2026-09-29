// Hot Death Uno - CI
// Scripted syntax on purpose: this Jenkins lacks pipeline-model-definition,
// so `pipeline { agent any }` will not parse. Controller is Linux, so `sh`.
// Plugins assumed (all verified installed on the instance): workflow-cps,
// workflow-basic-steps, workflow-durable-task-step, workflow-scm-step,
// workflow-support, script-security.
//
// SCOPING TRAP -- read before editing:
// Top-level `def` constants in a scripted Jenkinsfile compile to locals of
// run(), not script fields. A `def` method cannot read them; it looks them up
// in the Binding and throws MissingPropertyException. Every value a helper
// needs is therefore passed in as a parameter, and the constants live inside
// node{} so they are plain locals in scope at the call sites. Do not inline
// them back into the helper bodies.

def resolveGradle(String moduleDir, String cacheDir, String gradleVersion) {
  if (file("${moduleDir}/gradle/wrapper/gradle-wrapper.jar").exists()) {
    return './gradlew'
  }
  def dist = "${cacheDir}/gradle-${gradleVersion}"
  if (!file("${dist}/bin/gradle").exists()) {
    sh """
      set -euo pipefail
      mkdir -p '${cacheDir}'
      curl -fsSL -o '${dist}.zip' \
        'https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip'
      curl -fsSL -o '${dist}.zip.sha256' \
        'https://services.gradle.org/distributions/gradle-${gradleVersion}-bin.zip.sha256'
      cd '${cacheDir}' && sha256sum -c '${dist}.zip.sha256'
      unzip -q -o '${dist}.zip'
    """
  }
  return "${dist}/bin/gradle"
}

// 35.0.0 is AGP 8.11's *default* build-tools revision, and this project never
// sets buildToolsVersion, so AGP resolves 35.0.0 regardless of how new the
// installed revision is.
def resolveAndroidSdk(String cacheDir, String api, String buildTools) {
  def found = [env.ANDROID_HOME, env.ANDROID_SDK_ROOT, "${cacheDir}/android-sdk"]
    .findAll { it }
    .find { file("${it}/cmdline-tools").exists() || file("${it}/tools").exists() }
  if (found) {
    return found
  }
  def sdk = "${cacheDir}/android-sdk"
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
      'platform-tools' 'platforms;android-${api}' 'build-tools;${buildTools}'
  """
  return sdk
}

// AGP reads ANDROID_HOME, so local.properties is unnecessary -- which is what we
// want, since it is gitignored and holds a machine-specific SDK path.
def runGradle(String moduleDir, String gradleCmd, String sdkHome, String task) {
  dir(moduleDir) {
    withEnv(["ANDROID_HOME=${sdkHome}", "ANDROID_SDK_ROOT=${sdkHome}"]) {
      sh "${gradleCmd} --no-daemon --stacktrace ${task}"
    }
  }
}

node {
  def moduleDir = 'com.runtsoft.hotdeath'
  def cacheDir = "${env.JENKINS_HOME ?: '/var/lib/jenkins'}/.toolcache/hotdeath"
  def gradleVersion = '8.13'
  def api = '36'
  def buildTools = '35.0.0'
  def gradleCmd
  def sdkHome

  stage('Checkout') {
    checkout scm
  }

  stage('Toolchain') {
    sh 'java -version 2>&1 | head -n 3'
  }

  stage('Android SDK') {
    sdkHome = resolveAndroidSdk(cacheDir, api, buildTools)
    sh """
      set -eu
      test -d '${sdkHome}/platforms/android-${api}' || { echo "MISSING platform android-${api}"; exit 1; }
      test -d '${sdkHome}/build-tools/${buildTools}' || { echo "MISSING build-tools ${buildTools}"; exit 1; }
      echo "SDK verified at ${sdkHome}"
    """
  }

  stage('Gradle') {
    gradleCmd = resolveGradle(moduleDir, cacheDir, gradleVersion)
    sh "${gradleCmd} --version | sed -n '1,8p'"
  }

  stage('Unit tests') {
    runGradle(moduleDir, gradleCmd, sdkHome, 'testDebugUnitTest')
  }

  stage('Lint') {
    runGradle(moduleDir, gradleCmd, sdkHome, 'lintDebug')
  }

  stage('Assemble') {
    runGradle(moduleDir, gradleCmd, sdkHome, 'assembleDebug')
  }

  stage('Publish reports') {
    junit allowEmptyResults: true, testResults: "${moduleDir}/app/build/test-results/**/*.xml"
    archiveArtifacts artifacts: "${moduleDir}/app/build/reports/lint-results-debug.html",
                     allowEmptyArchive: true, fingerprint: true
  }

  stage('Archive') {
    def apks = file("${moduleDir}/app/build/outputs/apk/debug").listFiles()
    if (apks == null || apks.findAll { it.name.endsWith('.apk') }.isEmpty()) {
      echo 'WARNING: no APK produced, yet the build reported success'
      currentBuild.result = 'UNSTABLE'
    } else {
      archiveArtifacts artifacts: "${moduleDir}/app/build/outputs/apk/debug/*.apk", fingerprint: true
    }
  }
}
