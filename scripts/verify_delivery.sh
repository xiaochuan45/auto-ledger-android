#!/usr/bin/env bash
# Run against downloaded, already signed APKs. Never builds or signs an APK.
set -euo pipefail

delivery_dir="${1:?Usage: verify_delivery.sh DIRECTORY}"
app_id='io.github.xiaochuan45.autoledger'
runner="${app_id}.test/androidx.test.runner.AndroidJUnitRunner"
upgrade_class="${app_id}.data.UpgradePersistenceTest"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
read -r previous_version current_version < <(python3 "$script_dir/delivery_config.py" --codes)
mkdir -p "$delivery_dir/emulator-results"

instrument() {
    local report_name="$1"
    shift
    adb shell am instrument -w -r "$@" "$runner" | tee "$delivery_dir/emulator-results/$report_name.txt"
    python3 - "$delivery_dir/emulator-results/$report_name.txt" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
if (not re.search(r"OK \([1-9][0-9]* tests?\)", text)
        or "INSTRUMENTATION_CODE: -1" not in text
        or re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=|Process crashed", text)):
    raise SystemExit("Instrumentation did not complete with passing, non-empty tests")
PY
}

# The emulator is isolated and contains only fictional data. Seed only the stable
# upgrade fixture on the exact previously published APK: ordinary current tests
# may reference recovery classes that do not exist in that older application.
adb install "$delivery_dir/verification-previous.apk"
adb install -t "$delivery_dir/verification-instrumentation.apk"
adb shell dumpsys package "$app_id" > "$delivery_dir/emulator-results/previous-installed-package.txt"
python3 - "$delivery_dir/emulator-results/previous-installed-package.txt" "$previous_version" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
if not re.search(r"\bversionCode=" + re.escape(sys.argv[2]) + r"\b", text):
    raise SystemExit("The installed app is not the pinned previous release")
PY
instrument seed-before-upgrade -e class "$upgrade_class#seedBeforeUpgrade"
adb shell am force-stop "$app_id"
adb install -r "$delivery_dir/auto-ledger-prototype.apk" | tee "$delivery_dir/emulator-results/upgrade-install.txt"
instrument verify-after-upgrade -e class "$upgrade_class#dataSurvivesUpgrade"
instrument release-suite -e notClass "$upgrade_class"
adb shell dumpsys package "$app_id" > "$delivery_dir/emulator-results/installed-package.txt"
python3 - "$delivery_dir/emulator-results/installed-package.txt" "$current_version" <<'PY'
import pathlib
import re
import sys

text = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
if not re.search(r"\bversionCode=" + re.escape(sys.argv[2]) + r"\b", text):
    raise SystemExit("The installed app is not the exact current delivery version")
PY
echo "Signed delivery APK tests and data-preserving published versionCode $previous_version → $current_version update passed."
