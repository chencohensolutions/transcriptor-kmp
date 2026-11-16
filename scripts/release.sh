#!/usr/bin/env bash
# Versions and GitHub releases of the Android app. The version lives in version.properties.
#   ./scripts/release.sh bump patch|minor|major   raise versionName (0.2.0 -> 0.2.1 / 0.3.0 / 1.0.0) and versionCode (+1), and commit
#   ./scripts/release.sh publish                  tag the current commit v<versionName>, push the tag, build the release
#                                                 APK and publish it as a GitHub release (notes from the merged PRs)
# Publish runs on main, with a clean tree that matches origin/main. The release APK is signed with the build's
# signing config (today the debug key: fine to sideload, not for Play).
set -euo pipefail
cd "$(dirname "$0")/.."

file=version.properties
name=$(grep '^versionName=' "$file" | cut -d= -f2)
code=$(grep '^versionCode=' "$file" | cut -d= -f2)

case "${1:-}" in
    bump)
        IFS=. read -r major minor patch <<<"$name"
        case "${2:-}" in
            patch) patch=$((patch + 1)) ;;
            minor) minor=$((minor + 1)); patch=0 ;;
            major) major=$((major + 1)); minor=0; patch=0 ;;
            *) echo "Usage: $0 bump patch|minor|major" >&2; exit 1 ;;
        esac
        new="$major.$minor.$patch"
        sed -i.bak -e "s/^versionName=.*/versionName=$new/" -e "s/^versionCode=.*/versionCode=$((code + 1))/" "$file" && rm "$file.bak"
        git add "$file"
        git commit -m "Version $new ($((code + 1)))"
        echo "Now $new ($((code + 1))). Push it (through a PR to main), then: $0 publish"
        ;;
    publish)
        tag="v$name"
        branch=$(git rev-parse --abbrev-ref HEAD)
        [[ "$branch" == main ]] || { echo "Publish from main (on $branch)" >&2; exit 1; }
        [[ -z "$(git status --porcelain)" ]] || { echo "The tree has changes: commit or drop them first" >&2; exit 1; }
        git fetch -q origin main
        [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "main isn't origin/main: pull or push first" >&2; exit 1; }
        if git rev-parse -q --verify "refs/tags/$tag" >/dev/null || git ls-remote --exit-code --tags origin "$tag" >/dev/null; then
            echo "$tag exists already: bump first" >&2; exit 1
        fi
        if [[ -z "${JAVA_HOME:-}" ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; fi
        ./gradlew --console=plain :sharedLogic:testAndroidHostTest :androidApp:assembleRelease
        apk="build/release/AudioLogs-$name.apk"
        mkdir -p build/release
        cp androidApp/build/outputs/apk/release/androidApp-release.apk "$apk"
        git tag -a "$tag" -m "Audio Logs $name ($code)"
        git push origin "$tag"
        # Notes: the PRs merged since the previous version's tag (all of them for the first).
        previous=$(git tag -l 'v*' --sort=-v:refname | grep -vx "$tag" | head -1 || true)
        args=(--title "Audio Logs $name" --generate-notes)
        [[ -n "$previous" ]] && args+=(--notes-start-tag "$previous")
        gh release create "$tag" "$apk" "${args[@]}"
        ;;
    *)
        sed -n '2,7p' "$0" >&2
        exit 1
        ;;
esac
