#!/usr/bin/env bash
# Permanent LAB test download channel. Old Downloader codes and MADRE remain untouched.
set -euo pipefail
repo="$GITHUB_REPOSITORY"; run_id="$GITHUB_RUN_ID"; dir="$SOURCE_DIR"; branch="$GITHUB_REF_NAME"
channel="visionflix-lab-current"; asset="Verlezza-Vision-TV.apk"
url="https://github.com/$repo/releases/download/$channel/$asset"
cert="ae10b6198ccb567386af37ad8b92d9ad40aa5bada469b8528811d447fc9612f6"
test "$repo" = "Br174/Visionflix"
case "$branch" in lab/visionflix-*) ;; *) exit 71;; esac
grep -Fxq "AUTO_UPDATE_FIXED_LAB_URL=true" .motorlab/LAB_FIXED_DOWNLOADER_POLICY_V1.txt
grep -Fxq "PROMOTE_MAIN_MADRE_PRODUCTION=false" .motorlab/LAB_FIXED_DOWNLOADER_POLICY_V1.txt
grep -Fxq "PROTECTED_OLD_CODES=3875230,4768904,3938567" .motorlab/LAB_FIXED_DOWNLOADER_POLICY_V1.txt
for file in "$asset" "Verlezza-Vision-Mobile.apk" "LAB-APK-SHA256SUMS.txt" "apk-info.txt" "signature-info.txt" "signing-mode.txt"; do
  test -s "$dir/$file" || { echo "::error::Missing verified artifact $file"; exit 72; }
done
(cd "$dir" && sha256sum --status -c LAB-APK-SHA256SUMS.txt)
grep -Fq "$cert" "$dir/signature-info.txt"
grep -Fq "permanent-v1" "$dir/signing-mode.txt"
grep -Fq "package: name='com.br174.visionflix.tv.debug'" "$dir/apk-info.txt"
grep -Fq "application-label:'Verlezza Vision'" "$dir/apk-info.txt"
code="$(grep -oE "versionCode='[0-9]+'" "$dir/apk-info.txt" | head -1 | cut -d"'" -f2)"
[[ "$code" =~ ^[0-9]+$ ]] || { echo "::error::Invalid TV versionCode"; exit 73; }
expected="$(sha256sum "$dir/$asset" | awk '{print $1}')"
immutable="visionflix-lab-ci-$run_id"
body="$(gh release view "$immutable" -R "$repo" --json body,isDraft,isPrerelease --jq '. | select(.isDraft == false and .isPrerelease == true) | .body')"
grep -Fq "Source commit: $GITHUB_SHA." <<< "$body" || { echo "::error::Source prerelease provenance mismatch"; exit 74; }
mkdir -p "$RUNNER_TEMP/immutable"
gh release download "$immutable" -R "$repo" -p "$asset" -D "$RUNNER_TEMP/immutable"
test "$(sha256sum "$RUNNER_TEMP/immutable/$asset" | awk '{print $1}')" = "$expected" || { echo "::error::Source prerelease checksum mismatch"; exit 75; }
notes="$RUNNER_TEMP/lab-current-notes.txt"
cat > "$notes" <<EOF
Verlezza Vision TV LAB channel. TEST ONLY, not MADRE or production.
VERSION_CODE=$code
VERIFIED_TV_SHA256=$expected
SOURCE_BRANCH=$branch
SOURCE_COMMIT=$GITHUB_SHA
VERIFIED_RUN=https://github.com/$repo/actions/runs/$run_id
IMMUTABLE_SOURCE=https://github.com/$repo/releases/tag/$immutable
SIGNING_CERT_SHA256=$cert
PERMANENT_URL=$url
MUTABLE_TEST_CHANNEL=true
HISTORIC_DOWNLOADER_CODES_UNCHANGED=3875230,4768904,3938567
EOF
old_id=""; incoming_id=""
if ! gh release view "$channel" -R "$repo" >/dev/null 2>&1; then
  gh release create "$channel" "$dir/$asset" -R "$repo" --target main --title "Verlezza Vision LAB fixed channel ($code)" --notes-file "$notes" --prerelease --latest=false
else
  old_notes="$(gh release view "$channel" -R "$repo" --json body,isDraft,isPrerelease --jq '. | select(.isDraft == false and .isPrerelease == true) | .body')"
  old_code="$(sed -n 's/^VERSION_CODE=//p' <<< "$old_notes" | head -1)"
  [[ "$old_code" =~ ^[0-9]+$ ]] || { echo "::error::Existing channel not recognized, refusing overwrite"; exit 76; }
  if (( code <= old_code )); then echo "[LAST_GOOD] Stable code $old_code is newer or equal, preserving"; exit 0; fi
  release_id="$(gh api "repos/$repo/releases/tags/$channel" --jq .id)"
  old_id="$(gh api "repos/$repo/releases/$release_id/assets" --jq '.[] | select(.name == "Verlezza-Vision-TV.apk") | .id')"
  if [[ ! "$old_id" =~ ^[0-9]+$ ]]; then
    # Restore the missing canonical asset from this checksum-verified immutable build.
    # Do not delete or replace any existing downloadable APK.
    echo "[RECOVERY] Missing canonical APK; restoring verified LAB asset"
    gh release upload "$channel" "$dir/$asset" -R "$repo"
    restored_id="$(gh api "repos/$repo/releases/$release_id/assets" --jq '.[] | select(.name == "Verlezza-Vision-TV.apk") | .id')"
    [[ "$restored_id" =~ ^[0-9]+$ ]] || { echo "::error::Recovery upload unavailable"; exit 77; }
  else
  incoming="Verlezza-Vision-TV-INCOMING-$run_id.apk"
  cp "$dir/$asset" "$RUNNER_TEMP/$incoming"
  gh release upload "$channel" "$RUNNER_TEMP/$incoming" -R "$repo"
  incoming_id="$(gh api "repos/$repo/releases/$release_id/assets" --jq '.[] | .name + "=" + (.id|tostring)' | awk -F= -v n="$incoming" '$1 == n {print $2}')"
  [[ "$incoming_id" =~ ^[0-9]+$ ]] || { echo "::error::Staged asset unavailable"; exit 78; }
  mkdir -p "$RUNNER_TEMP/staged"
  gh release download "$channel" -R "$repo" -p "$incoming" -D "$RUNNER_TEMP/staged"
  test "$(sha256sum "$RUNNER_TEMP/staged/$incoming" | awk '{print $1}')" = "$expected" || { echo "::error::Staged asset checksum mismatch"; exit 79; }
  backup="Verlezza-Vision-TV-PREV-$old_code-$run_id.apk"
  gh api -X PATCH "repos/$repo/releases/assets/$old_id" -f name="$backup" --jq .name
  if ! gh api -X PATCH "repos/$repo/releases/assets/$incoming_id" -f name="$asset" --jq .name; then
    gh api -X PATCH "repos/$repo/releases/assets/$old_id" -f name="$asset" --jq .name
    echo "::error::Stable cutover failed; LAST_GOOD restored"; exit 80
  fi
  fi
fi
verified=0
for attempt in 1 2 3 4 5; do
  if curl --fail --location --retry 2 --max-time 120 --silent --show-error "$url" --output "$RUNNER_TEMP/verified-current.apk"; then
    actual="$(sha256sum "$RUNNER_TEMP/verified-current.apk" | awk '{print $1}')"
    if [[ "$actual" == "$expected" ]]; then verified=1; break; fi
  fi
done
if [[ "$verified" != 1 ]]; then
  if [[ -n "$old_id" && -n "$incoming_id" ]]; then
    gh api -X PATCH "repos/$repo/releases/assets/$incoming_id" -f name="Verlezza-Vision-TV-FAILED-$run_id.apk" --jq .name || true
    gh api -X PATCH "repos/$repo/releases/assets/$old_id" -f name="$asset" --jq .name || true
  fi
  echo "::error::Permanent LAB URL checksum invalid; no new code announced"; exit 81
fi
gh release edit "$channel" -R "$repo" --title "Verlezza Vision LAB fixed channel ($code)" --notes-file "$notes" --prerelease --latest=false
echo "[VERIFIED_FIXED_LAB] code=$code sha256=$expected url=$url"
{ echo "### VERIFIED permanent LAB Downloader URL"; echo "$url"; echo "versionCode=$code sha256=$expected"; echo "Historic numeric codes unchanged"; } >> "$GITHUB_STEP_SUMMARY"
