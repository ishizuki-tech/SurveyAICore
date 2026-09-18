#!/usr/bin/env bash
set -euo pipefail

package_name="com.negi.surveyaicore.usertest"
model_file_name="model.litertlm"
partial_file_name="model.litertlm.partial"
expected_size="4919541760"
expected_sha256="2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4"
minimum_free_bytes="13509476352"

usage() {
    echo "Usage: provision-model.sh <verified-model-path> [device-serial]" >&2
    exit 2
}

fail() { echo "ERROR: $*" >&2; exit 1; }

[[ $# -ge 1 && $# -le 2 ]] || usage
model_path="$1"
serial="${2:-${ANDROID_SERIAL:-}}"
command -v adb >/dev/null 2>&1 || fail "adb is required"
[[ -f "$model_path" ]] || fail "Model path is not a regular file: $model_path"
[[ "$(stat -f '%z' "$model_path")" == "$expected_size" ]] || fail "Host model size mismatch"
[[ "$(shasum -a 256 "$model_path" | awk '{print $1}')" == "$expected_sha256" ]] || fail "Host model SHA-256 mismatch"

if [[ -z "$serial" ]]; then
    devices=()
    while read -r device state _; do
        [[ "$state" == "device" ]] && devices+=("$device")
    done < <(adb devices)
    [[ ${#devices[@]} -eq 1 ]] || fail "Exactly one adb device is required; pass its serial"
    serial="${devices[0]}"
fi
[[ "$(adb -s "$serial" get-state 2>/dev/null || true)" == "device" ]] || fail "Device $serial is not ready"
adb -s "$serial" shell pm path "$package_name" >/dev/null 2>&1 || fail "Install user-test-app first"
adb -s "$serial" shell run-as "$package_name" true || fail "run-as is unavailable"
free_bytes="$(adb -s "$serial" shell df -k /data/user/0 | awk 'NR == 2 { print $4 * 1024 }')"
[[ -n "$free_bytes" && "$free_bytes" -ge "$minimum_free_bytes" ]] || fail "Insufficient device storage"

if adb -s "$serial" shell run-as "$package_name" test -e "files/$model_file_name"; then
    fail "Existing final model is preserved; do not overwrite it"
fi
if adb -s "$serial" shell run-as "$package_name" test -e "files/$partial_file_name"; then
    fail "Existing partial model is preserved; do not overwrite it"
fi

adb -s "$serial" shell run-as "$package_name" mkdir -p files
cat -- "$model_path" | adb -s "$serial" exec-in "run-as $package_name sh -c 'cat > files/$partial_file_name'" || fail "Transfer failed; partial file was preserved"
device_size="$(adb -s "$serial" shell run-as "$package_name" stat -c '%s' "files/$partial_file_name")"
[[ "$device_size" == "$expected_size" ]] || fail "Partial model size mismatch"
device_sha="$(adb -s "$serial" shell run-as "$package_name" sha256sum "files/$partial_file_name" | awk '{print $1}')"
[[ "$device_sha" == "$expected_sha256" ]] || fail "Partial model SHA-256 mismatch"
adb -s "$serial" shell run-as "$package_name" mv "files/$partial_file_name" "files/$model_file_name"
echo "MODEL_READY package=$package_name serial=$serial size=$device_size sha256=$device_sha"
