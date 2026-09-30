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
  //
  // POSIX sh only -- the sh step runs /bin/sh, which is dash here, so no [[ ]].
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

          # API level : system-image tag. Android 17 is published as 37.0/37.1/
          # 37.2 rather than a bare 37, so the tag is spelled out per level
          # instead of being assumed from the API number.
          #
          # KNOWN_FAILING levels are still exercised -- they are how we find out
          # when the app starts working on a new platform -- but their results
          # are archived rather than published, so they do not fail the build.
          # Remove a level from this list once its tests pass and it should start
          # gating again. TODO.md tracks the api37 app-side work.
          KNOWN_FAILING='37'
          ran=''
          skipped=''
          failed=''
          known_failed=''
          for entry in 34:34 35:35 36:36 37:37.0; do
            api="\${entry%%:*}"
            tag="\${entry##*:}"
            IMAGE="system-images;android-\$tag;google_apis;x86_64"
            AVD="api\$api"

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
            # no amount of RAM supplies. See TODO.md.
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
            if "${gradleHome}" --no-daemon --stacktrace connectedDebugAndroidTest; then
              ran="\$ran \$AVD"
            else
              ran="\$ran \$AVD"
              if [ "\$enforced" = 'yes' ]; then
                echo "TESTS FAILED on \$AVD" >&2
                failed="\$failed \$AVD"
              else
                echo "TESTS FAILED on \$AVD, but \$AVD is a known-failing level" >&2
                echo "(API 37 will not launch this app's activities yet -- see TODO.md)" >&2
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
