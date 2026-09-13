#!/usr/bin/env bash
# Puts locally-held manga pages on the device for judging V2 quality.
#
# The pages are copyrighted and are never committed — `comic-sample/` is in
# .gitignore. This script is committed; what it pushes is not.
#
# Two destinations, because the two things that read them differ:
#   - the instrumentation harness reads the test app's own external files dir,
#     which needs no storage permission
#   - the browser reads /sdcard/Download, so a page can be looked at by eye
#
# Manga is drawn portrait. The emulator is rotated to match, because fitting a
# ~1600x2048 page onto a landscape screen shrinks it to a third and OCR then
# fails for reasons that have nothing to do with the material.
set -euo pipefail

ADB="${ADB:-adb}"
SRC="${1:-comic-sample}"

TEST_DIR=/sdcard/Android/data/com.babel.platform.capture.test/files/comic-sample
VIEW_DIR=/sdcard/Download/comic-sample

if [ ! -d "$SRC" ]; then
    echo "no material at $SRC — supply pages there (they stay out of git)" >&2
    exit 1
fi

"$ADB" shell mkdir -p "$TEST_DIR" "$VIEW_DIR"

for image in "$SRC"/*.{jpg,jpeg,png,webp}; do
    [ -e "$image" ] || continue
    name=$(basename "$image")
    "$ADB" push "$image" "$TEST_DIR/$name" >/dev/null
    "$ADB" push "$image" "$VIEW_DIR/$name" >/dev/null

    # One viewer per page: full width, no margin, so the page is read at the
    # size a reader would actually see rather than letterboxed.
    html="<title>${name}</title><style>html,body{margin:0;background:#fff}
img{width:100%;display:block}</style><img src=\"${name}\">"
    echo "$html" | "$ADB" shell "cat > $VIEW_DIR/${name%.*}.html"
    echo "pushed $name"
done

# Portrait, and hold it there.
"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0

echo
echo "harness:  ./gradlew :platform:capture:connectedDebugAndroidTest"
echo "by eye:   $ADB shell am start -n com.android.chromium/org.chromium.chrome.browser.ChromeTabbedActivity \\"
echo "            -a android.intent.action.VIEW -d 'file://$VIEW_DIR/<page>.html'"
