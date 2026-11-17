#!/usr/bin/env bash
# Versions and GitHub releases of the Android app. The version lives in version.properties.
#   ./scripts/release.sh bump patch|minor|major   raise versionName (0.2.0 -> 0.2.1 / 0.3.0 / 1.0.0) and versionCode (+1), and commit
#   ./scripts/release.sh publish                  tag the current commit v<versionName>, push the tag, build the release
#                                                 APK and publish it as a GitHub release (notes from the merged PRs)
#   ./scripts/release.sh ci                       what the release workflow runs when main is merged into `releases`
# The usual way to release is merging main into the `releases` branch: .github/workflows/release.yml runs `ci`, which
# takes the version from version.properties, or the next free patch when that one was released already (and a
# versionCode above every released one), signs with the release key from the repository's secrets, tags and publishes.
# Without RELEASE_KEYSTORE_FILE in the environment the APK is signed with the debug key (fine to sideload, not an
# update of a release-signed install).
set -euo pipefail
cd "$(dirname "$0")/.."

file=version.properties
name=$(grep '^versionName=' "$file" | cut -d= -f2)
code=$(grep '^versionCode=' "$file" | cut -d= -f2)

tag_exists() {
    git rev-parse -q --verify "refs/tags/$1" >/dev/null || git ls-remote --exit-code --tags origin "$1" >/dev/null
}

# Tests, the release APK, the tag v$name pushed, and the GitHub release with the APK (notes: the PRs merged since the
# previous version's tag, all of them for the first).
build_and_publish() {
    local tag="v$name"
    ./gradlew --console=plain :sharedLogic:testAndroidHostTest :androidApp:assembleRelease
    apk="build/release/AudioLogs-$name.apk"
    mkdir -p build/release
    cp androidApp/build/outputs/apk/release/androidApp-release.apk "$apk"
    [[ "${DRY_RUN:-}" == 1 ]] && { echo "Dry run: built $apk ($name, $code), nothing published"; return; }
    git tag -a "$tag" -m "Audio Logs $name ($code)"
    git push origin "$tag"
    local previous
    previous=$(git tag -l 'v*' --sort=-v:refname | grep -vx "$tag" | head -1 || true)
    local args=(--title "Audio Logs $name" --generate-notes)
    [[ -n "$previous" ]] && args+=(--notes-start-tag "$previous")
    gh release create "$tag" "$apk" "${args[@]}"
}

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
        echo "Now $new ($((code + 1))). Push it (through a PR to main), then merge main into releases"
        ;;
    publish)
        branch=$(git rev-parse --abbrev-ref HEAD)
        [[ "$branch" == main ]] || { echo "Publish from main (on $branch)" >&2; exit 1; }
        [[ -z "$(git status --porcelain)" ]] || { echo "The tree has changes: commit or drop them first" >&2; exit 1; }
        git fetch -q origin main
        [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "main isn't origin/main: pull or push first" >&2; exit 1; }
        tag_exists "v$name" && { echo "v$name exists already: bump first" >&2; exit 1; }
        if [[ -z "${JAVA_HOME:-}" ]]; then export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; fi
        build_and_publish
        ;;
    ci)
        git fetch -q --tags origin
        # A commit released already (the workflow run again, or releases pushed without new commits): nothing to do.
        if released=$(git tag --points-at HEAD -l 'v*' | head -1) && [[ -n "$released" ]]; then
            echo "This commit is $released already: nothing to release"; exit 0
        fi
        # The version: version.properties', or the next free patch of it when it was released (main not bumped).
        IFS=. read -r major minor patch <<<"$name"
        while tag_exists "v$major.$minor.$patch"; do patch=$((patch + 1)); done
        [[ "$major.$minor.$patch" != "$name" ]] && echo "v$name was released already: this one is $major.$minor.$patch"
        name="$major.$minor.$patch"
        # versionCode above every released one (each tag's message ends with "(<versionCode>)").
        for t in $(git tag -l 'v*'); do
            c=$(git tag -l --format='%(contents:subject)' "$t" | sed -n 's/.*(\([0-9]*\))$/\1/p')
            [[ -n "$c" && "$c" -ge "$code" ]] && code=$((c + 1))
        done
        # Only in the workflow's checkout: main's version.properties stays as it is.
        sed -i.bak -e "s/^versionName=.*/versionName=$name/" -e "s/^versionCode=.*/versionCode=$code/" "$file" && rm "$file.bak"
        echo "Releasing $name ($code)"
        build_and_publish
        ;;
    *)
        sed -n '2,10p' "$0" >&2
        exit 1
        ;;
esac
