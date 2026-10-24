#!/usr/bin/env bash
# MotorLab Visionflix: LAB-only mutable download slot, immutable historical prereleases stay intact.
set -euo pipefail
repo="Br174/Visionflix"
tag="visionflix-tv-lab-current"
asset="Verlezza-Vision-TV-LAB.apk"
cert="ae10b6198ccb567386af37ad8b92d9ad40aa5bada469b8528811d447fc9612f6"
run_id="${LAB_SOURCE_RUN_ID:?Missing verified source run}"
workdir="${RUNNER_TEMP:-/tmp}/visionflix-lab-current-${run_id}"
[[ "$GITHUB_REPOSITORY" == "$repo" ]] || { echo "::error::wrong repository"; exit 60; }
[[ "$GITHUB_REF_NAME" == lab/visionflix-* ]] || { echo "::error::not a Visionflix LAB branch"; exit 61; }
[[ -f .motorlab/LAB_AUTOPUBLISH_POLICY.txt ]] || { echo "::error::missing LAB policy"; exit 62; }
grep -Fxq 'AUTO_PUBLISH_VERIFIED_LAB=true' .motorlab/LAB_AUTOPUBLISH_POLICY.txt
grep -Fxq 'PROMOTE_MADRE=false' .motorlab/LAB_AUTOPUBLISH_POLICY.txt
mkdir -p "$workdir"
run_record="$(gh api "repos/$repo/actions/runs/$run_id")"
actual_state="$(jq -r '.conclusion // ""' <<< "$run_record")"
actual_branch="$(jq -r '.head_branch // ""' <<< "$run_record")"
actual_sha="$(jq -r '.head_sha // ""' <<< "$run_record")"
[[ "$actual_state" == success ]] || { echo "::error::build run not successful"; exit 63; }
[[ "$actual_branch" == lab/visionflix-* ]] || { echo "::error::source is not an authorized LAB"; exit 64; }
[[ "$actual_sha" =~ ^[a-f0-9]{40}$ ]] || { echo "::error::invalid source SHA"; exit 65; }
artifact="$(gh api "repos/$repo/actions/runs/$run_id/artifacts" --jq '.artifacts[] | select(.expired == false and (.name | startswith("Verlezza-Vision-LAB-"))) | .name' | head -n 1)"
[[ -n "$artifact" ]] || { echo "::error::verified APK artifact not available"; exit 66; }
gh run download "$run_id" --repo "$repo" --name "$artifact" --dir "$workdir"
test -s "$workdir/Verlezza-Vision-TV.apk"
test -s "$workdir/Verlezza-Vision-Mobile.apk"
[[ "$(tr -d '\r\n' < "$workdir/signing-mode.txt")" == "permanent-v1" ]]
grep -Fq "$cert" "$workdir/signature-info.txt"
grep -Fq "$cert" "$workdir/mobile-signature-info.txt"
# All source APKs must be positive-size, and TV must match its original uploaded digest.
tv_sha="$(sha256sum "$workdir/Verlezza-Vision-TV.apk" | awk '{print $1}')"
mobile_sha="$(sha256sum "$workdir/Verlezza-Vision-Mobile.apk" | awk '{print $1}')"
assert_sha_from_manifest="$(awk '/Verlezza-Vision-TV.apk/ {print $1; exit}' "$workdir/SHA256SUMS.txt")"
[[ "$tv_sha" == "$assert_sha_from_manifest" ]] || { echo "::error::APK sha mismatch"; exit 67; }

notes="$workdir/NOTES.md"
cat > "$notes" <<EOF
# Verlezza Vision LAB corrente (test-only)
Questo collegamento fornisce l'ultima LAB validata dalla pipeline, **non** la MADRE.
Run GitHub verificato: https://github.com/$repo/actions/runs/$run_id
Commit APK sorgente: $actual_sha
SHA-256 TV: $tv_sha
SHA-256 Mobile (verifica interna): $mobile_sha
Impronta firma V1: $cert
Stable URL: https://github.com/$repo/releases/download/$tag/$asset

RUN_ID=$run_id
I codici delle distribuzioni precedenti e la MADRE non vengono modificati.
Se la TV ha una versione firmata con un vecchio certificato Android, prima
dell'eventuale disinstallazione salvare dati e impostazioni importanti.
EOF

if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
    old_body="$(gh release view "$tag" --repo "$repo" --json body --jq .body)"
    old_run="$(printf '%s\n' "$old_body" | sed -n 's/^RUN_ID=\([0-9][0-9]*\)$/\1/p' | head -n 1)"
    if [[ -n "$old_run" ]] && (( old_run >= run_id )); then
        echo "Existing slot is at run $old_run, no rollback to $run_id" >> "$GITHUB_STEP_SUMMARY"
        exit 0
    fi
    # Keep an old validated file on the runner for recovery if upload fails.
    if gh release view "$tag" --repo "$repo" --json assets --jq '.assets[].name' | grep -Fxq "$asset"; then
        gh release download "$tag" --repo "$repo" --pattern "$asset" --dir "$workdir/previous" 2>/dev/null || true
    fi
    if ! gh release upload "$tag" "$workdir/Verlezza-Vision-TV.apk#$asset" \
        --repo "$repo" --clobber; then
        if [[ -s "$workdir/previous/$asset" ]]; then
            gh release upload "$tag" "$workdir/previous/$asset#$asset" --repo "$repo" --clobber || true
        fi
        echo "::error::LAB download slot update failed; previous archived release not modified"
        exit 68
    fi
    gh release edit "$tag" --repo "$repo" --notes-file "$notes" \
      --prerelease --latest=false
else
    # Target MAIN is a tag anchor only: no source, main, or production write.
    gh release create "$tag" "$workdir/Verlezza-Vision-TV.apk#$asset" \
      --repo "$repo" --target main \
      --title "Verlezza Vision TV: LAB corrente (test)" \
      --notes-file "$notes" --prerelease --latest=false
fi

readback="$(gh api "repos/$repo/releases/tags/$tag")"
actual_sha="$(jq -r --arg n "$asset" '.assets[] | select(.name == $n) | .digest' <<< "$readback")"
[[ "$actual_sha" == "sha256:$tv_sha" ]] || { echo "::error::LAB slot integrity verification failed"; exit 69; }
[[ "$(jq -r '.prerelease' <<< "$readback")" == true ]] || exit 70
[[ "$(jq -r '.draft' <<< "$readback")" == false ]] || exit 71
latest="$(gh api "repos/$repo/releases/latest" --jq .tag_name)"
[[ "$latest" != "$tag" ]] || { echo "::error::LAB inadvertently became stable latest"; exit 72; }
echo "Verified reusable LAB link: https://github.com/$repo/releases/download/$tag/$asset" >> "$GITHUB_STEP_SUMMARY"
echo "Pinned APK SHA-256: $tv_sha" >> "$GITHUB_STEP_SUMMARY"
echo "Historical releases, MADRE, production and old Downloader codes untouched." >> "$GITHUB_STEP_SUMMARY"
