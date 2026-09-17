#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.negi.surveyaicore.test"
RUNNER_NAME="androidx.test.runner.AndroidJUnitRunner"
INSTRUMENTATION_COMPONENT="$PACKAGE_NAME/$RUNNER_NAME"
TEST_METHOD="coreOwnedPreflight"
SUITE_NAME="PREFLIGHT"
MODEL_FILE_NAME="c7-model.litertlm"
PARTIAL_FILE_NAME="c7-model.litertlm.partial"
FILES_DIRECTORY="files"
EXPECTED_MODEL_SIZE="4919541760"
EXPECTED_MODEL_SHA256="2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4"
EXPECTED_BRANCH="codex/device-characterization"
EXPECTED_BASE="993c7b0294c42f7e61663bbac9cf69ab90890aae"
RUN_TIMEOUT_SECONDS=300

usage() {
    cat <<'EOF'
Usage:
  run-c7-preflight.sh <model-path> <device-serial>
  run-c7-preflight.sh --check <model-path> <device-serial>
  run-c7-preflight.sh --evaluator <model-path> <device-serial>
  run-c7-preflight.sh --generator <model-path> <device-serial>
  run-c7-preflight.sh --orchestration <model-path> <device-serial>

Normal mode builds the Core Android-test APK, replacement-installs only the
C7-owned test application, provisions only files/c7-model.litertlm after exact
identity checks, then runs exactly one bounded C7 preflight instrumentation
method. It never touches the C5/C6 application or its app-private model.
The --evaluator mode runs exactly the four C7 evaluator characterization cases.
The --generator mode runs exactly the four C7 generator characterization cases.
The --orchestration mode runs the single C7 orchestration characterization scenario.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

MODE="run"
case "${1:-}" in
    --check)
        MODE="check"
        shift
        ;;
    --evaluator)
        TEST_METHOD="evaluatorCharacterization"
        SUITE_NAME="EVALUATOR_CHARACTERIZATION"
        shift
        ;;
    --generator)
        TEST_METHOD="generatorCharacterization"
        SUITE_NAME="GENERATOR_CHARACTERIZATION"
        shift
        ;;
    --orchestration)
        TEST_METHOD="orchestrationCharacterization"
        SUITE_NAME="ORCHESTRATION_CHARACTERIZATION"
        shift
        ;;
esac

TEST_CLASS="com.negi.surveyaicore.evaluation.C7PreflightInstrumentationTest#$TEST_METHOD"

[[ $# -eq 2 ]] || {
    usage >&2
    exit 2
}

MODEL_PATH="$1"
SERIAL="$2"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
TEST_APK="$REPO_ROOT/survey-ai-core/build/outputs/apk/androidTest/debug/survey-ai-core-debug-androidTest.apk"
EVIDENCE_DIR="$(mktemp -d /private/tmp/survey-ai-core-c7.XXXXXX)"

cleanup() {
    echo "C7_EVIDENCE_DIR=$EVIDENCE_DIR"
}
trap cleanup EXIT

command -v adb >/dev/null 2>&1 || fail "adb is required on PATH"
[[ -f "$MODEL_PATH" ]] || fail "Model path is not a regular file: $MODEL_PATH"

actual_branch="$(git -C "$REPO_ROOT" branch --show-current)"
actual_head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
[[ "$actual_branch" == "$EXPECTED_BRANCH" ]] || fail "Expected branch $EXPECTED_BRANCH, found $actual_branch"
git -C "$REPO_ROOT" merge-base --is-ancestor "$EXPECTED_BASE" HEAD || \
    fail "HEAD $actual_head does not descend from expected base $EXPECTED_BASE"

host_size="$(stat -f '%z' "$MODEL_PATH")"
[[ "$host_size" == "$EXPECTED_MODEL_SIZE" ]] || \
    fail "Host model size mismatch: expected $EXPECTED_MODEL_SIZE, found $host_size"
host_sha="$(shasum -a 256 "$MODEL_PATH" | awk '{print $1}')"
[[ "$host_sha" == "$EXPECTED_MODEL_SHA256" ]] || fail "Host model SHA-256 mismatch"

[[ "$(adb -s "$SERIAL" get-state 2>/dev/null || true)" == "device" ]] || \
    fail "Device $SERIAL is not ready"

device_model="$(adb -s "$SERIAL" shell getprop ro.product.model | tr -d '\r')"
android_version="$(adb -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
sdk="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
cat >"$EVIDENCE_DIR/preflight.txt" <<EOF
BRANCH=$actual_branch
HEAD=$actual_head
EXPECTED_BASE=$EXPECTED_BASE
MODEL_SOURCE=$MODEL_PATH
MODEL_SIZE=$host_size
MODEL_SHA256=$host_sha
SERIAL=$SERIAL
DEVICE_MODEL=$device_model
ANDROID_VERSION=$android_version
SDK=$sdk
PACKAGE=$PACKAGE_NAME
MODEL_DESTINATION=$FILES_DIRECTORY/$MODEL_FILE_NAME
EOF

run_as() {
    adb -s "$SERIAL" shell run-as "$PACKAGE_NAME" "$@"
}

device_file_size() {
    run_as stat -c '%s' "$FILES_DIRECTORY/$1"
}

device_file_sha256() {
    run_as sha256sum "$FILES_DIRECTORY/$1" | awk '{print $1}'
}

verify_device_file() {
    local name="$1"
    [[ "$(device_file_size "$name")" == "$EXPECTED_MODEL_SIZE" ]] || return 1
    [[ "$(device_file_sha256 "$name")" == "$EXPECTED_MODEL_SHA256" ]] || return 1
}

if [[ "$MODE" == "check" ]]; then
    if adb -s "$SERIAL" shell pm path "$PACKAGE_NAME" >/dev/null 2>&1 && run_as pwd >/dev/null 2>&1; then
        if run_as test -f "$FILES_DIRECTORY/$MODEL_FILE_NAME" && verify_device_file "$MODEL_FILE_NAME"; then
            echo "C7_CHECK_COMPLETE_MODEL_READY"
        else
            echo "C7_CHECK_COMPLETE_MODEL_NOT_READY"
        fi
    else
        echo "C7_CHECK_COMPLETE_PACKAGE_NOT_INSTALLED"
    fi
    exit 0
fi

"$REPO_ROOT/gradlew" :survey-ai-core:assembleDebugAndroidTest
[[ -f "$TEST_APK" ]] || fail "C7 test APK was not produced"

adb -s "$SERIAL" install -r "$TEST_APK" >"$EVIDENCE_DIR/install.txt"
run_as pwd >/dev/null 2>&1 || fail "run-as is unavailable for $PACKAGE_NAME"
run_as mkdir -p "$FILES_DIRECTORY"

if run_as test -f "$FILES_DIRECTORY/$MODEL_FILE_NAME"; then
    verify_device_file "$MODEL_FILE_NAME" || \
        fail "Existing C7 final model does not match; preserving it without overwrite"
elif run_as test -f "$FILES_DIRECTORY/$PARTIAL_FILE_NAME"; then
    verify_device_file "$PARTIAL_FILE_NAME" || \
        fail "Existing C7 partial model does not match; preserving it without overwrite"
    run_as mv "$FILES_DIRECTORY/$PARTIAL_FILE_NAME" "$FILES_DIRECTORY/$MODEL_FILE_NAME"
else
    if ! cat -- "$MODEL_PATH" | adb -s "$SERIAL" exec-in \
        "run-as $PACKAGE_NAME sh -c 'cat > $FILES_DIRECTORY/$PARTIAL_FILE_NAME'"; then
        fail "C7 model transfer failed; partial file was preserved and not finalized"
    fi
    run_as test -f "$FILES_DIRECTORY/$PARTIAL_FILE_NAME" || fail "C7 transfer produced no partial file"
    verify_device_file "$PARTIAL_FILE_NAME" || fail "C7 partial model identity mismatch"
    run_as mv "$FILES_DIRECTORY/$PARTIAL_FILE_NAME" "$FILES_DIRECTORY/$MODEL_FILE_NAME"
fi
verify_device_file "$MODEL_FILE_NAME" || fail "C7 final model identity mismatch"

adb -s "$SERIAL" logcat -v threadtime -d >"$EVIDENCE_DIR/logcat-before.txt"
adb -s "$SERIAL" shell am instrument -w -e class "$TEST_CLASS" "$INSTRUMENTATION_COMPONENT" \
    >"$EVIDENCE_DIR/instrumentation.txt" 2>&1 &
instrumentation_pid=$!
deadline=$((SECONDS + RUN_TIMEOUT_SECONDS))
while kill -0 "$instrumentation_pid" 2>/dev/null; do
    if (( SECONDS >= deadline )); then
        kill "$instrumentation_pid" 2>/dev/null || true
        adb -s "$SERIAL" shell am force-stop "$PACKAGE_NAME" || true
        fail "C7 instrumentation exceeded ${RUN_TIMEOUT_SECONDS}s"
    fi
    sleep 1
done
wait "$instrumentation_pid" || fail "C7 instrumentation failed; see $EVIDENCE_DIR/instrumentation.txt"
adb -s "$SERIAL" logcat -v threadtime -d >"$EVIDENCE_DIR/logcat-after.txt"
rg "SurveyAICoreC7|configuredBackend=|effectiveBackend=|GPU initialization failed" \
    "$EVIDENCE_DIR/logcat-after.txt" >"$EVIDENCE_DIR/c7-runtime-evidence.txt" || true

echo "C7_${SUITE_NAME}_PASS"
