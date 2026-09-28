#!/usr/bin/env bash
# Vendors Faceclaw's Kotlin core and its Android BLE glue, unchanged, from a Faceclaw checkout.
#
#   scripts/sync-faceclaw.sh /path/to/faceclaw [commit-ish]     (default: 0.8.0)
#
# Afterwards: update faceclaw-core/UPSTREAM.md, and keep the firmware revision in step
# (FirmwareCatalog.CUSTOM_REVISION + patch set) with the revision the new core requires
# (app/g2/firmware-compat.ts: REQUIRED_FACECLAW_FIRMWARE_VERSION). Then run
#   ./gradlew :faceclaw-core:testAndroidHostTest :app:testDebugUnitTest
set -euo pipefail

src=${1:?usage: sync-faceclaw.sh /path/to/faceclaw [commit-ish]}
rev=${2:-0.8.0}
root=$(cd "$(dirname "$0")/.." && pwd)
commit=$(git -C "$src" rev-parse "$rev^{commit}")
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

git -C "$src" archive "$commit" \
    native/kotlin/shared/src/commonMain native/kotlin/shared/src/androidMain \
    tests/kotlin/src/commonTest tests/kotlin/src/androidHostTest | tar -x -C "$tmp"

core="$root/faceclaw-core/src"
rm -rf "$core"
mkdir -p "$core"
mv "$tmp/native/kotlin/shared/src/commonMain" "$core/commonMain"
mv "$tmp/native/kotlin/shared/src/androidMain" "$core/androidMain"
mv "$tmp/tests/kotlin/src/commonTest" "$core/commonTest"
mv "$tmp/tests/kotlin/src/androidHostTest" "$core/androidHostTest"
# FontTest needs a 6 MB font from Faceclaw's app/ and a generated path; it is left out.
rm -f "$core/commonTest/kotlin/com/faceclaw/app/FontTest.kt"

android="$root/faceclaw-android/src/main/java/com/faceclaw/app"
rm -rf "$root/faceclaw-android/src"
mkdir -p "$android"
for f in FaceclawBleManager.kt AndroidSessionLink.kt AndroidStockLink.kt FaceclawDeviceInfoProbe.kt; do
    git -C "$src" show "$commit:App_Resources/Android/src/main/java/com/faceclaw/app/$f" > "$android/$f"
done

required=$(git -C "$src" show "$commit:app/g2/firmware-compat.ts" | sed -n 's/.*REQUIRED_FACECLAW_FIRMWARE_VERSION = \([0-9]*\).*/\1/p')
catalog=$(sed -n 's/.*const val CUSTOM_REVISION = \([0-9]*\).*/\1/p' "$root/firmware-image/src/main/kotlin/ch/madtreasures/g2watch/firmware/FirmwareCatalog.kt")
echo "Vendored Faceclaw $rev ($commit)."
if [ -z "$required" ]; then
    echo "ERROR: could not read REQUIRED_FACECLAW_FIRMWARE_VERSION from app/g2/firmware-compat.ts" >&2
    exit 1
fi
echo "This core requires custom firmware revision $required."
if [ "$required" != "$catalog" ]; then
    echo "ERROR: FirmwareCatalog.CUSTOM_REVISION is $catalog. Update the patch set and FirmwareCatalog to revision $required" >&2
    echo "       (see faceclaw-core/UPSTREAM.md) before building: core and firmware must match exactly." >&2
    exit 1
fi
