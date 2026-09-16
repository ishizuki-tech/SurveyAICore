#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="com.negi.surveyaicore.c5harness"
MODEL_FILE_NAME="model.litertlm"
PARTIAL_FILE_NAME="model.litertlm.partial"
FILES_DIRECTORY="files"
EXPECTED_SIZE="4919541760"
EXPECTED_SHA256="2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4"
MIN_FREE_BYTES="13509476352"

usage() {
    cat <<'EOF'
Usage:
  provision-model.sh <model-path> [device-serial]
  provision-model.sh --check <model-path> [device-serial]

Normal mode is a C5C2 operation: it verifies the supplied model, streams it into
app-private storage, verifies the partial file, atomically finalizes it, and
verifies the final file. --check performs read-only prerequisite validation.
EOF
}

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

MODE="provision"
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

validate_host_model() {
    [[ -e "$MODEL_PATH" ]] || fail "Model path does not exist: $MODEL_PATH"
    [[ -f "$MODEL_PATH" ]] || fail "Model path is not a regular file: $MODEL_PATH"

    local size
    local sha
    size="$(stat -f '%z' "$MODEL_PATH")"
    [[ "$size" == "$EXPECTED_SIZE" ]] || \
        fail "Host model size mismatch: expected $EXPECTED_SIZE, found $size"
    sha="$(shasum -a 256 "$MODEL_PATH" | awk '{print $1}')"
    [[ "$sha" == "$EXPECTED_SHA256" ]] || \
        fail "Host model SHA-256 mismatch: expected $EXPECTED_SHA256, found $sha"
    echo "HOST_MODEL_VERIFIED size=$size sha256=$sha"
}

package_installed() {
    adb -s "$SERIAL" shell pm path "$PACKAGE_NAME" >/dev/null 2>&1
}

run_as() {
    adb -s "$SERIAL" shell run-as "$PACKAGE_NAME" "$@"
}

device_free_bytes() {
    adb -s "$SERIAL" shell df -k /data/user/0 | awk 'NR == 2 { print $4 * 1024 }'
}

files_directory_state() {
    local state
    # A fixed shell predicate is required to distinguish missing from a
    # non-directory without treating a failed stat invocation as absence.
    if ! state="$(adb -s "$SERIAL" shell "run-as $PACKAGE_NAME sh -c 'if [ -L $FILES_DIRECTORY ]; then printf \"%s\\n\" CONFLICT; elif [ -d $FILES_DIRECTORY ]; then printf \"%s\\n\" DIRECTORY; elif [ -e $FILES_DIRECTORY ]; then printf \"%s\\n\" CONFLICT; else printf \"%s\\n\" ABSENT; fi'" 2>&1)"; then
        echo "ERROR: Could not classify app-private $FILES_DIRECTORY directory state: $state" >&2
        return 1
    fi

    case "$state" in
        ABSENT|DIRECTORY|CONFLICT)
            echo "$state"
            ;;
        *)
            echo "ERROR: Unexpected app-private $FILES_DIRECTORY directory classifier output: $state" >&2
            return 1
            ;;
    esac
}

prepare_files_directory() {
    local state
    state="$(files_directory_state)" || \
        fail "Could not classify app-private $FILES_DIRECTORY directory state"
    case "$state" in
        DIRECTORY)
            echo "FILES_DIR_PRESENT"
            echo "FILES_DIR_READY"
            ;;
        ABSENT)
            if [[ "$MODE" == "check" ]]; then
                echo "FILES_DIR_ABSENT_WILL_CREATE_IN_NORMAL_MODE"
                return 0
            fi
            run_as mkdir -p "$FILES_DIRECTORY" || \
                fail "Could not create app-private $FILES_DIRECTORY directory"
            echo "FILES_DIR_CREATED"
            state="$(files_directory_state)" || \
                fail "Could not reclassify app-private $FILES_DIRECTORY directory after creation"
            [[ "$state" == "DIRECTORY" ]] || \
                fail "App-private $FILES_DIRECTORY path is not an observable directory after creation"
            echo "FILES_DIR_READY"
            ;;
        CONFLICT)
            fail "App-private $FILES_DIRECTORY path exists but is not a directory; preserving it without overwrite"
            ;;
        *)
            fail "Unexpected app-private $FILES_DIRECTORY directory state: $state"
            ;;
    esac
}

device_file_exists() {
    run_as stat -c '%s' "$FILES_DIRECTORY/$1" >/dev/null 2>&1
}

device_file_size() {
    run_as stat -c '%s' "$FILES_DIRECTORY/$1"
}

device_file_sha256() {
    run_as sha256sum "$FILES_DIRECTORY/$1" | awk '{print $1}'
}

verify_device_file() {
    local marker="$1"
    local file_name="$2"
    local size
    local sha
    size="$(device_file_size "$file_name")"
    [[ "$size" == "$EXPECTED_SIZE" ]] || \
        fail "$marker size mismatch: expected $EXPECTED_SIZE, found $size"
    echo "${marker}_SIZE_VERIFIED size=$size"
    sha="$(device_file_sha256 "$file_name")"
    [[ "$sha" == "$EXPECTED_SHA256" ]] || \
        fail "$marker SHA-256 mismatch: expected $EXPECTED_SHA256, found $sha"
    echo "${marker}_SHA_VERIFIED sha256=$sha"
}

inspect_existing_files() {
    local directory_state
    directory_state="$(files_directory_state)" || \
        fail "Could not classify app-private $FILES_DIRECTORY directory before model inspection"
    if [[ "$directory_state" == "ABSENT" ]]; then
        echo "NO_DEVICE_MODEL_FILES"
        return 0
    fi

    if device_file_exists "$MODEL_FILE_NAME"; then
        echo "FINAL_MODEL_PRESENT"
        if verify_device_file "FINAL" "$MODEL_FILE_NAME"; then
            echo "MODEL_ALREADY_VERIFIED"
            return 10
        fi
        return 11
    fi

    if device_file_exists "$PARTIAL_FILE_NAME"; then
        echo "PARTIAL_MODEL_PRESENT"
        if verify_device_file "PARTIAL" "$PARTIAL_FILE_NAME"; then
            return 12
        fi
        return 13
    fi

    echo "NO_DEVICE_MODEL_FILES"
    return 0
}

validate_host_model
resolve_serial

FREE_BYTES="$(device_free_bytes)"
[[ -n "$FREE_BYTES" ]] || fail "Could not determine available device storage"
echo "DEVICE_FREE_BYTES available=$FREE_BYTES minimum=$MIN_FREE_BYTES"

if ! package_installed; then
    if [[ "$MODE" == "check" ]]; then
        echo "PACKAGE_NOT_INSTALLED"
        echo "CHECK_COMPLETE_WITH_PREREQUISITE_GAP"
        exit 0
    fi
    fail "Package $PACKAGE_NAME is not installed; install APKs before provisioning"
fi
echo "PACKAGE_READY"

run_as pwd >/dev/null 2>&1 || fail "run-as is unavailable for $PACKAGE_NAME"
echo "RUN_AS_READY"

if [[ "$FREE_BYTES" -lt "$MIN_FREE_BYTES" ]]; then
    fail "Insufficient device storage: available $FREE_BYTES, minimum $MIN_FREE_BYTES"
fi
echo "STORAGE_VERIFIED"

prepare_files_directory

set +e
inspect_existing_files
existing_status=$?
set -e

if [[ "$MODE" == "check" ]]; then
    case "$existing_status" in
        0)
            echo "CHECK_COMPLETE_NO_DEVICE_MODEL"
            ;;
        10)
            echo "CHECK_COMPLETE_FINAL_MODEL_VERIFIED"
            ;;
        12)
            echo "CHECK_COMPLETE_PARTIAL_MODEL_VERIFIED"
            ;;
        *)
            echo "CHECK_COMPLETE_DEVICE_MODEL_MISMATCH"
            ;;
    esac
    exit 0
fi

case "$existing_status" in
    10)
        exit 0
        ;;
    11)
        fail "Existing final model does not match; preserving it without overwrite"
        ;;
    12)
        echo "PARTIAL_MODEL_VERIFIED"
        ;;
    13)
        fail "Existing partial model does not match; preserving it without overwrite"
        ;;
    0)
        echo "TRANSFER_BEGIN"
        REMOTE_TRANSFER_COMMAND="run-as $PACKAGE_NAME sh -c 'cat > $FILES_DIRECTORY/$PARTIAL_FILE_NAME'"
        if ! cat -- "$MODEL_PATH" | adb -s "$SERIAL" exec-in "$REMOTE_TRANSFER_COMMAND"; then
            fail "Model transfer failed; partial file was preserved and not finalized"
        fi
        # adb exec-in does not reliably return the inner shell failure status.
        # Observable partial existence below is therefore required before completion.
        device_file_exists "$PARTIAL_FILE_NAME" || \
            fail "Model transfer completed without creating the partial file"
        echo "TRANSFER_COMPLETE"
        verify_device_file "PARTIAL" "$PARTIAL_FILE_NAME"
        ;;
    *)
        fail "Unexpected existing-model inspection status: $existing_status"
        ;;
esac

if device_file_exists "$MODEL_FILE_NAME"; then
    fail "Final model appeared before finalization; preserving both paths without overwrite"
fi

run_as mv "$FILES_DIRECTORY/$PARTIAL_FILE_NAME" "$FILES_DIRECTORY/$MODEL_FILE_NAME"
echo "MODEL_FINALIZED"
verify_device_file "FINAL" "$MODEL_FILE_NAME"
run_as ls -l "$FILES_DIRECTORY/$MODEL_FILE_NAME"
echo "MODEL_READY"
