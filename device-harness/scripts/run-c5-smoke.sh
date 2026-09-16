#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.negi.surveyaicore.c5harness"
TEST_PACKAGE="com.negi.surveyaicore.c5harness.test"
RUNNER_NAME="androidx.test.runner.AndroidJUnitRunner"
INSTRUMENTATION_COMPONENT="$TEST_PACKAGE/$RUNNER_NAME"
TEST_CLASS="com.negi.surveyaicore.c5harness.C5SmokeInstrumentationTest#publicSmoke"

usage() {
    cat <<'EOF'
Usage:
  run-c5-smoke.sh <model-path> [device-serial]
  run-c5-smoke.sh --check <model-path> [device-serial]

Normal mode is a C5C2 operation: it refreshes the assembled AAR, builds and
installs both APKs, provisions the verified model, and runs one required-model
instrumentation smoke. --check performs read-only prerequisite validation.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

MODE="run"
case "${1:-}" in
    --help|-h)
        usage
        exit 0
        ;;
    --check)
        MODE="check"
        shift
        ;;
esac

[[ $# -ge 1 && $# -le 2 ]] || {
    usage >&2
    exit 2
}

MODEL_PATH="$1"
EXPLICIT_SERIAL="${2:-}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HARNESS_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd -- "$HARNESS_ROOT/.." && pwd)"
PROVISION_SCRIPT="$SCRIPT_DIR/provision-model.sh"
PREPARE_AAR_SCRIPT="$SCRIPT_DIR/prepare-aar.sh"
MAIN_APK="$HARNESS_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$HARNESS_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

[[ -x "$PROVISION_SCRIPT" ]] || fail "Provisioning script is not executable: $PROVISION_SCRIPT"
[[ -x "$PREPARE_AAR_SCRIPT" ]] || fail "AAR preparation script is not executable: $PREPARE_AAR_SCRIPT"
[[ -x "$HARNESS_ROOT/gradlew" ]] || fail "Harness Gradle wrapper is not executable"
command -v adb >/dev/null 2>&1 || fail "adb is required on PATH"

resolve_serial() {
    if [[ -n "$EXPLICIT_SERIAL" ]]; then
        SERIAL="$EXPLICIT_SERIAL"
    elif [[ -n "${ANDROID_SERIAL:-}" ]]; then
        SERIAL="$ANDROID_SERIAL"
    else
        local serial
        local state
        local ignored
        local devices=()
        while read -r serial state ignored; do
            [[ "$state" == "device" ]] && devices+=("$serial")
        done < <(adb devices)
        [[ ${#devices[@]} -eq 1 ]] || fail "Exactly one usable adb device is required; pass a serial explicitly"
        SERIAL="${devices[0]}"
    fi

    [[ "$(adb -s "$SERIAL" get-state 2>/dev/null || true)" == "device" ]] || \
        fail "Device $SERIAL is not in adb device state"
    echo "DEVICE_SELECTED serial=$SERIAL"
}

generate_run_id() {
    if command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr '[:upper:]' '[:lower:]'
    else
        printf '%s-%s-%s\n' "$(date '+%s')" "$$" "$RANDOM"
    fi
}

resolve_serial

if [[ "$MODE" == "check" ]]; then
    [[ -f "$PREPARE_AAR_SCRIPT" ]] || fail "Missing AAR preparation script"
    [[ -f "$PROVISION_SCRIPT" ]] || fail "Missing provisioning script"
    echo "REPOSITORY_ROOT=$REPO_ROOT"
    echo "HARNESS_ROOT=$HARNESS_ROOT"
    echo "MAIN_APK_EXPECTED=$MAIN_APK"
    echo "TEST_APK_EXPECTED=$TEST_APK"
    echo "INSTRUMENTATION_COMPONENT=$INSTRUMENTATION_COMPONENT"
    echo "CLASS_FILTER=$TEST_CLASS"
    echo "REQUIRE_MODEL_ARGUMENT=c5.requireModel=true"
    "$PROVISION_SCRIPT" --check "$MODEL_PATH" "$SERIAL"
    echo "CHECK_COMPLETE"
    exit 0
fi

"$PREPARE_AAR_SCRIPT"
"$HARNESS_ROOT/gradlew" -p "$HARNESS_ROOT" :app:assembleDebug
"$HARNESS_ROOT/gradlew" -p "$HARNESS_ROOT" :app:assembleDebugAndroidTest

[[ -f "$MAIN_APK" ]] || fail "Main APK was not produced: $MAIN_APK"
[[ -f "$TEST_APK" ]] || fail "Test APK was not produced: $TEST_APK"

adb -s "$SERIAL" install -r "$MAIN_APK"
adb -s "$SERIAL" install -r -t "$TEST_APK"

"$PROVISION_SCRIPT" "$MODEL_PATH" "$SERIAL"

INSTRUMENTATION_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/surveyaicore-c5-smoke.XXXXXX")"
LOGCAT_OUTPUT="$(mktemp "${TMPDIR:-/tmp}/surveyaicore-c5-logcat.XXXXXX")"
CURRENT_RUN_LOG="$(mktemp "${TMPDIR:-/tmp}/surveyaicore-c5-current-run.XXXXXX")"
BOUNDARY_TAG="SurveyAICoreC5Boundary"
BOUNDARY_MARKER="C5C_RUN_BOUNDARY_$(generate_run_id)"

adb -s "$SERIAL" shell log -t "$BOUNDARY_TAG" "$BOUNDARY_MARKER" || \
    fail "Could not emit current-run log boundary"

set +e
adb -s "$SERIAL" shell am instrument -w -r \
    -e class "$TEST_CLASS" \
    -e c5.requireModel true \
    "$INSTRUMENTATION_COMPONENT" >"$INSTRUMENTATION_OUTPUT" 2>&1
instrumentation_status=$?
set -e
cat "$INSTRUMENTATION_OUTPUT"
adb -s "$SERIAL" logcat -d -v threadtime >"$LOGCAT_OUTPUT"
awk -v marker="$BOUNDARY_MARKER" '
    index($0, marker) {
        found = 1
        next
    }
    found { print }
    END { if (!found) exit 1 }
' "$LOGCAT_OUTPUT" >"$CURRENT_RUN_LOG" || \
    fail "Current-run log boundary was not found; evidence: $LOGCAT_OUTPUT"

[[ "$instrumentation_status" -eq 0 ]] || fail "Instrumentation exited $instrumentation_status; evidence: $INSTRUMENTATION_OUTPUT"
grep -Fq 'OK (1 test)' "$INSTRUMENTATION_OUTPUT" || fail "Selected smoke did not report one successful test"
if grep -Eqi 'skipped|ignored|assumptionviolated|failure|instrumentation_failed|instrumentation_aborted' "$INSTRUMENTATION_OUTPUT"; then
    fail "Instrumentation output indicates skipped or failed execution"
fi
if grep -Fq 'MODEL_MISSING_SKIP' "$INSTRUMENTATION_OUTPUT" || grep -Fq 'MODEL_MISSING_SKIP' "$CURRENT_RUN_LOG"; then
    fail "Model-missing skip evidence is not acceptable for C5C2"
fi
for marker in CREATE_BEGIN CREATE_SUCCESS GENERATE_BEGIN GENERATE_COMPLETE CLOSE_BEGIN CLOSE_SUCCESS TERMINAL_PASS; do
    grep -Fq "$marker" "$CURRENT_RUN_LOG" || fail "Required lifecycle marker missing: $marker"
done

echo "C5_SMOKE_PASS instrumentation_output=$INSTRUMENTATION_OUTPUT logcat_output=$LOGCAT_OUTPUT current_run_log=$CURRENT_RUN_LOG"
