#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.negi.surveyaicore.c5harness"
TEST_PACKAGE="com.negi.surveyaicore.c5harness.test"
RUNNER_NAME="androidx.test.runner.AndroidJUnitRunner"
INSTRUMENTATION_COMPONENT="$TEST_PACKAGE/$RUNNER_NAME"
MODEL_FILE_NAME="model.litertlm"
MODEL_PATH="files/$MODEL_FILE_NAME"
EXPECTED_MODEL_SIZE="4919541760"
EXPECTED_MODEL_SHA256="2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4"
EXPECTED_BRANCH="phase-c6-runtime-validation"
EXPECTED_SERIAL="55191JEBF03764"
EXPECTED_DEVICE_MODEL="Pixel 9a"
EXPECTED_ANDROID_VERSION="16"
EXPECTED_SDK="36"
BOUNDARY_TAG="SurveyAICoreC6Boundary"

usage() {
    cat <<'EOF'
Usage:
  run-c6.sh --scenario foundation|c6b-warm-reuse|c6c-cancellation-recovery|c6d-create-close-lifecycle|c6d-lifecycle-recovery-supplemental [--serial <serial>] [--model-check fast|full] [--expected-base <sha>] [--check]

This C6 script has no default scenario. `foundation` is the C6A non-inference
selector. `c6b-warm-reuse` is the reviewed C6B selector for one GPU instance
and exactly three sequential requests. `c6c-cancellation-recovery` is the
reviewed C6C selector for two event-driven cancellations and two sequential
same-instance recovery requests. `c6d-create-close-lifecycle` is the reviewed
C6D selector for three independent create/generate/close cycles, lifecycle
cancellation after GENERATE_BEGIN, and one fresh recovery request.
`c6d-lifecycle-recovery-supplemental` runs only the reviewed lifecycle
cancellation and fresh recovery portions of C6D. Normal mode builds the assembled AAR,
replacement-installs the harness APKs, preserves existing app data, and runs
exactly one selected instrumentation method. It never provisions, replaces,
or deletes a model.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

SCENARIO=""
EXPLICIT_SERIAL=""
MODEL_CHECK="fast"
EXPECTED_BASE=""
CHECK_ONLY=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenario)
            [[ $# -ge 2 ]] || fail "--scenario requires a value"
            SCENARIO="$2"
            shift 2
            ;;
        --serial)
            [[ $# -ge 2 ]] || fail "--serial requires a value"
            EXPLICIT_SERIAL="$2"
            shift 2
            ;;
        --model-check)
            [[ $# -ge 2 ]] || fail "--model-check requires fast or full"
            MODEL_CHECK="$2"
            shift 2
            ;;
        --expected-base)
            [[ $# -ge 2 ]] || fail "--expected-base requires a commit SHA"
            EXPECTED_BASE="$2"
            shift 2
            ;;
        --check)
            CHECK_ONLY=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "$SCENARIO" ]] || {
    usage >&2
    fail "An explicit --scenario is required"
}
[[ "$MODEL_CHECK" == "fast" || "$MODEL_CHECK" == "full" ]] || fail "--model-check must be fast or full"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HARNESS_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd -- "$HARNESS_ROOT/.." && pwd)"
PREPARE_AAR_SCRIPT="$SCRIPT_DIR/prepare-aar.sh"
MAIN_APK="$HARNESS_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$HARNESS_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SOURCE_AAR="$REPO_ROOT/survey-ai-core/build/outputs/aar/survey-ai-core-release.aar"
HARNESS_AAR="$HARNESS_ROOT/app/libs/survey-ai-core-release.aar"

case "$SCENARIO" in
    foundation)
        TEST_CLASS="com.negi.surveyaicore.c6harness.C6FoundationInstrumentationTest#eventRecorderKeepsHarnessIdentitiesOrderedAndFilterable"
        ;;
    c6b-warm-reuse)
        TEST_CLASS="com.negi.surveyaicore.c6harness.C6WarmReuseInstrumentationTest#warmReuseSequential"
        ;;
    c6c-cancellation-recovery)
        TEST_CLASS="com.negi.surveyaicore.c6harness.C6CancellationRecoveryInstrumentationTest#cancellationRecovery"
        ;;
    c6d-create-close-lifecycle)
        TEST_CLASS="com.negi.surveyaicore.c6harness.C6CreateCloseLifecycleInstrumentationTest#createCloseLifecycleRecovery"
        ;;
    c6d-lifecycle-recovery-supplemental)
        TEST_CLASS="com.negi.surveyaicore.c6harness.C6CreateCloseLifecycleInstrumentationTest#lifecycleCancellationAndRecoveryOnly"
        ;;
    *)
        fail "Scenario '$SCENARIO' is not implemented by this reviewed C6A script"
        ;;
esac

command -v adb >/dev/null 2>&1 || fail "adb is required on PATH"
[[ -x "$PREPARE_AAR_SCRIPT" ]] || fail "AAR preparation script is not executable"
[[ -x "$HARNESS_ROOT/gradlew" ]] || fail "Harness Gradle wrapper is not executable"

resolve_serial() {
    REQUESTED_SERIAL="AUTO"
    SERIAL=""
    SERIAL_SELECTION_ERROR=""
    if [[ -n "$EXPLICIT_SERIAL" ]]; then
        REQUESTED_SERIAL="$EXPLICIT_SERIAL"
        SERIAL="$EXPLICIT_SERIAL"
    elif [[ -n "${ANDROID_SERIAL:-}" ]]; then
        REQUESTED_SERIAL="$ANDROID_SERIAL"
        SERIAL="$ANDROID_SERIAL"
    else
        local serial state ignored
        local devices=()
        while read -r serial state ignored; do
            [[ "$state" == "device" ]] && devices+=("$serial")
        done < <(adb devices)
        if [[ ${#devices[@]} -eq 1 ]]; then
            SERIAL="${devices[0]}"
        else
            SERIAL_SELECTION_ERROR="Exactly one usable adb device is required; pass --serial explicitly"
        fi
    fi
}

run_as() {
    adb -s "$SERIAL" shell run-as "$PACKAGE_NAME" "$@"
}

verify_source_baseline() {
    local actual_branch actual_head worktree_state staged_state base_ancestry="NO" failure=""
    actual_branch="$(git -C "$REPO_ROOT" branch --show-current)"
    actual_head="$(git -C "$REPO_ROOT" rev-parse HEAD)"
    if [[ -n "$(git -C "$REPO_ROOT" status --porcelain=v1 --untracked-files=all)" ]]; then
        worktree_state="DIRTY"
    else
        worktree_state="CLEAN"
    fi
    if [[ -n "$(git -C "$REPO_ROOT" diff --cached --name-only)" ]]; then
        staged_state="STAGED"
    else
        staged_state="NONE"
    fi

    [[ "$actual_branch" == "$EXPECTED_BRANCH" ]] || failure="Expected branch $EXPECTED_BRANCH, found $actual_branch"
    if [[ -z "$EXPECTED_BASE" ]]; then
        failure="${failure:+$failure; }Expected base could not be resolved"
    elif git -C "$REPO_ROOT" merge-base --is-ancestor "$EXPECTED_BASE" HEAD; then
        base_ancestry="YES"
    else
        failure="${failure:+$failure; }HEAD does not descend from expected base $EXPECTED_BASE"
    fi

    cat >"$SOURCE_BASELINE_OUTPUT" <<EOF
ROOT=$REPO_ROOT
BRANCH=$actual_branch
HEAD=$actual_head
EXPECTED_BRANCH=$EXPECTED_BRANCH
EXPECTED_BASE=${EXPECTED_BASE:-UNAVAILABLE}
BASE_ANCESTRY=$base_ancestry
WORKTREE_STATE=$worktree_state
STAGED_STATE=$staged_state
SESSION_ID=$SESSION_ID
SCENARIO=$SCENARIO
FAILURE_REASON=${failure:-NONE}
VERDICT=$([[ -z "$failure" ]] && echo PASS || echo FAIL)
EOF

    [[ -z "$failure" ]] || fail "Source baseline verification failed; evidence: $SOURCE_BASELINE_OUTPUT"
    echo "SOURCE_BASELINE_OK head=$actual_head base=$EXPECTED_BASE"
}

verify_device() {
    local adb_state="UNAVAILABLE" model="UNAVAILABLE" android_version="UNAVAILABLE" sdk="UNAVAILABLE"
    local package_state="ABSENT" run_as_state="FAIL" failure=""
    local serial_match="NO" model_match="NO" android_version_match="NO" sdk_match="NO"

    if [[ -z "$SERIAL" ]]; then
        failure="$SERIAL_SELECTION_ERROR"
    else
        adb_state="$(adb -s "$SERIAL" get-state 2>&1 | tr -d '\r' || true)"
        if [[ "$adb_state" == "device" ]]; then
            model="$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
            android_version="$(adb -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
            sdk="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
            adb -s "$SERIAL" shell pm path "$PACKAGE_NAME" >/dev/null 2>&1 && package_state="PRESENT"
            run_as pwd >/dev/null 2>&1 && run_as_state="PASS"
        fi
        [[ "$adb_state" == "device" ]] || failure="Device $SERIAL is not ready"
        [[ "$SERIAL" == "$EXPECTED_SERIAL" ]] && serial_match="YES"
        [[ "$model" == "$EXPECTED_DEVICE_MODEL" ]] && model_match="YES"
        [[ "$android_version" == "$EXPECTED_ANDROID_VERSION" ]] && android_version_match="YES"
        [[ "$sdk" == "$EXPECTED_SDK" ]] && sdk_match="YES"
        [[ "$serial_match" == "YES" ]] || failure="${failure:+$failure; }Expected serial $EXPECTED_SERIAL, found '$SERIAL'"
        [[ "$model_match" == "YES" ]] || failure="${failure:+$failure; }Expected $EXPECTED_DEVICE_MODEL, found '$model'"
        [[ "$android_version_match" == "YES" ]] || failure="${failure:+$failure; }Expected Android $EXPECTED_ANDROID_VERSION, found '$android_version'"
        [[ "$sdk_match" == "YES" ]] || failure="${failure:+$failure; }Expected SDK $EXPECTED_SDK, found '$sdk'"
        [[ "$package_state" == "PRESENT" ]] || failure="${failure:+$failure; }Package $PACKAGE_NAME is not installed"
        [[ "$run_as_state" == "PASS" ]] || failure="${failure:+$failure; }run-as is unavailable for $PACKAGE_NAME"
    fi

    cat >"$DEVICE_PREFLIGHT_OUTPUT" <<EOF
REQUESTED_SERIAL=$REQUESTED_SERIAL
EXPECTED_SERIAL=$EXPECTED_SERIAL
ACTUAL_SERIAL=${SERIAL:-UNAVAILABLE}
SERIAL_MATCH=$serial_match
ADB_STATE=$adb_state
EXPECTED_DEVICE_MODEL=$EXPECTED_DEVICE_MODEL
ACTUAL_DEVICE_MODEL=$model
DEVICE_MODEL_MATCH=$model_match
EXPECTED_ANDROID_VERSION=$EXPECTED_ANDROID_VERSION
ACTUAL_ANDROID_VERSION=$android_version
ANDROID_VERSION_MATCH=$android_version_match
EXPECTED_SDK=$EXPECTED_SDK
ACTUAL_SDK=$sdk
SDK_MATCH=$sdk_match
PACKAGE_STATE=$package_state
RUN_AS=$run_as_state
FREE_BYTES=NOT_CHECKED
FAILURE_REASON=${failure:-NONE}
VERDICT=$([[ -z "$failure" ]] && echo PASS || echo FAIL)
EOF

    [[ -z "$failure" ]] || fail "Device preflight failed; evidence: $DEVICE_PREFLIGHT_OUTPUT"
}

verify_model() {
    local phase="$1" output phase_label size="UNAVAILABLE" sha="NOT_CHECKED"
    local present="NO" size_match="NO" sha_match="NOT_CHECKED" verdict="FAIL"
    case "$phase" in
        preinstall)
            output="$MODEL_PREINSTALL_OUTPUT"
            phase_label="PREINSTALL"
            ;;
        postinstall)
            output="$MODEL_POSTINSTALL_OUTPUT"
            phase_label="POSTINSTALL"
            ;;
        *)
            fail "Unknown model evidence phase: $phase"
            ;;
    esac

    size="$(run_as stat -c '%s' "$MODEL_PATH" 2>/dev/null || true)"
    if [[ -n "$size" ]]; then
        present="YES"
    else
        size="UNAVAILABLE"
    fi
    [[ "$size" == "$EXPECTED_MODEL_SIZE" ]] && size_match="YES"
    if [[ "$MODEL_CHECK" == "full" ]]; then
        sha="$(run_as sha256sum "$MODEL_PATH" 2>/dev/null | awk '{print $1}' || true)"
        [[ -n "$sha" ]] || sha="UNAVAILABLE"
        sha_match="NO"
        [[ "$sha" == "$EXPECTED_MODEL_SHA256" ]] && sha_match="YES"
    fi
    if [[ "$present" == "YES" && "$size_match" == "YES" && ( "$MODEL_CHECK" == "fast" || "$sha_match" == "YES" ) ]]; then
        verdict="PASS"
    fi

    cat >"$output" <<EOF
PHASE=$phase_label
MODEL_PATH=$MODEL_PATH
CHECK_LEVEL=$(tr '[:lower:]' '[:upper:]' <<<"$MODEL_CHECK")
PRESENT=$present
SIZE=$size
EXPECTED_SIZE=$EXPECTED_MODEL_SIZE
SIZE_MATCH=$size_match
SHA256=$sha
EXPECTED_SHA256=$([[ "$MODEL_CHECK" == "full" ]] && echo "$EXPECTED_MODEL_SHA256" || echo NOT_CHECKED)
SHA_MATCH=$sha_match
VERDICT=$verdict
EOF

    [[ "$verdict" == "PASS" ]] || fail "Model identity verification failed; evidence: $output"
    echo "MODEL_IDENTITY_OK phase=$phase_label level=$MODEL_CHECK size=$size"
}

record_aar_identity() {
    local source_regular="NO" source_nonempty="NO" source_size="" source_sha=""
    local harness_regular="NO" harness_nonempty="NO" harness_size="" harness_sha=""
    local size_equal="NO" sha_equal="NO" byte_identical="NO" verdict="FAIL"

    if [[ -f "$SOURCE_AAR" && ! -L "$SOURCE_AAR" ]]; then
        source_regular="YES"
        if [[ -s "$SOURCE_AAR" ]]; then
            source_nonempty="YES"
            source_size="$(stat -f '%z' "$SOURCE_AAR")"
            source_sha="$(shasum -a 256 "$SOURCE_AAR" | awk '{print $1}')"
        fi
    fi
    if [[ -f "$HARNESS_AAR" && ! -L "$HARNESS_AAR" ]]; then
        harness_regular="YES"
        if [[ -s "$HARNESS_AAR" ]]; then
            harness_nonempty="YES"
            harness_size="$(stat -f '%z' "$HARNESS_AAR")"
            harness_sha="$(shasum -a 256 "$HARNESS_AAR" | awk '{print $1}')"
        fi
    fi

    if [[ "$source_nonempty" == "YES" && "$harness_nonempty" == "YES" ]]; then
        [[ "$source_size" == "$harness_size" ]] && size_equal="YES"
        [[ "$source_sha" == "$harness_sha" ]] && sha_equal="YES"
        cmp -s "$SOURCE_AAR" "$HARNESS_AAR" && byte_identical="YES"
    fi
    if [[ "$size_equal" == "YES" && "$sha_equal" == "YES" && "$byte_identical" == "YES" ]]; then
        verdict="PASS"
    fi

    cat >"$AAR_IDENTITY_OUTPUT" <<EOF
SOURCE_AAR_PATH=$SOURCE_AAR
SOURCE_AAR_REGULAR=$source_regular
SOURCE_AAR_NONEMPTY=$source_nonempty
SOURCE_AAR_SIZE=$source_size
SOURCE_AAR_SHA256=$source_sha
HARNESS_AAR_PATH=$HARNESS_AAR
HARNESS_AAR_REGULAR=$harness_regular
HARNESS_AAR_NONEMPTY=$harness_nonempty
HARNESS_AAR_SIZE=$harness_size
HARNESS_AAR_SHA256=$harness_sha
SIZE_EQUAL=$size_equal
SHA_EQUAL=$sha_equal
BYTE_IDENTICAL=$byte_identical
VERDICT=$verdict
EOF

    [[ "$verdict" == "PASS" ]] || fail "AAR identity verification failed; evidence: $AAR_IDENTITY_OUTPUT"
}

SESSION_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
BOUNDARY_MARKER="C6_RUN_BOUNDARY_${SESSION_ID}_${SCENARIO}"
EVIDENCE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/surveyaicore-c6-evidence.${SESSION_ID}.XXXXXX")"
SOURCE_BASELINE_OUTPUT="$EVIDENCE_DIR/source-baseline.txt"
DEVICE_PREFLIGHT_OUTPUT="$EVIDENCE_DIR/device-preflight.txt"
MODEL_PREINSTALL_OUTPUT="$EVIDENCE_DIR/model-identity-preinstall.txt"
AAR_IDENTITY_OUTPUT="$EVIDENCE_DIR/aar-identity.txt"
MODEL_POSTINSTALL_OUTPUT="$EVIDENCE_DIR/model-identity-postinstall.txt"
INSTRUMENTATION_OUTPUT="$EVIDENCE_DIR/instrumentation.txt"
LOGCAT_OUTPUT="$EVIDENCE_DIR/logcat.txt"
BOUNDARY_LOGCAT_OUTPUT="$EVIDENCE_DIR/boundary-logcat.txt"
HARNESS_EVENTS_OUTPUT="$EVIDENCE_DIR/harness-events.txt"

if [[ -z "$EXPECTED_BASE" ]]; then
    EXPECTED_BASE="$(git -C "$REPO_ROOT" rev-parse phase-c5-device-harness 2>/dev/null || true)"
fi
verify_source_baseline
resolve_serial
verify_device
verify_model preinstall

if [[ "$CHECK_ONLY" == true ]]; then
    echo "CHECK_COMPLETE scenario=$SCENARIO serial=$SERIAL evidence=$EVIDENCE_DIR"
    exit 0
fi

"$PREPARE_AAR_SCRIPT"
record_aar_identity
"$HARNESS_ROOT/gradlew" -p "$HARNESS_ROOT" :app:assembleDebug
"$HARNESS_ROOT/gradlew" -p "$HARNESS_ROOT" :app:assembleDebugAndroidTest
[[ -f "$MAIN_APK" ]] || fail "Main APK was not produced"
[[ -f "$TEST_APK" ]] || fail "Test APK was not produced"

adb -s "$SERIAL" install -r "$MAIN_APK"
adb -s "$SERIAL" install -r -t "$TEST_APK"
verify_model postinstall

adb -s "$SERIAL" shell log -t "$BOUNDARY_TAG" "$BOUNDARY_MARKER" || fail "Could not emit C6 evidence boundary"
set +e
adb -s "$SERIAL" shell am instrument -w -r -e class "$TEST_CLASS" "$INSTRUMENTATION_COMPONENT" >"$INSTRUMENTATION_OUTPUT" 2>&1
instrumentation_status=$?
set -e
cat "$INSTRUMENTATION_OUTPUT"
adb -s "$SERIAL" logcat -d -v threadtime >"$LOGCAT_OUTPUT"
awk -v marker="$BOUNDARY_MARKER" '
    index($0, marker) { found = 1; next }
    found { print }
    END { if (!found) exit 1 }
' "$LOGCAT_OUTPUT" >"$BOUNDARY_LOGCAT_OUTPUT" || fail "Current C6 log boundary was not found"
grep -F 'C6_EVENT ' "$BOUNDARY_LOGCAT_OUTPUT" >"$HARNESS_EVENTS_OUTPUT" || true

[[ "$instrumentation_status" -eq 0 ]] || fail "Instrumentation exited $instrumentation_status; evidence: $INSTRUMENTATION_OUTPUT"
grep -Fq 'OK (1 test)' "$INSTRUMENTATION_OUTPUT" || fail "Selected C6 scenario did not report one successful test"
if grep -Eqi 'skipped|ignored|assumptionviolated|failure|instrumentation_failed|instrumentation_aborted' "$INSTRUMENTATION_OUTPUT"; then
    fail "Instrumentation output indicates skipped or failed execution"
fi

echo "C6_SCENARIO_PASS scenario=$SCENARIO session=$SESSION_ID evidence=$EVIDENCE_DIR logcat=$BOUNDARY_LOGCAT_OUTPUT"
