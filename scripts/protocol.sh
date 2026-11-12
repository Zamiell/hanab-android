#!/usr/bin/env bash
# Keeps the app's types for hanab.live's wire protocol in step with the website's own (macOS / Linux / Git Bash;
# scripts/protocol.ps1 does the same on Windows). See that script for what each command is for.
#
#   ./scripts/protocol.sh gen         write Protocol.kt from the pinned commit
#   ./scripts/protocol.sh check       fail if Protocol.kt is not what gen writes
#   ./scripts/protocol.sh outdated    fail if the protocol of the website that is live has changed since the pin
#   ./scripts/protocol.sh bump [rev]  move the pin (default: the commit the website is running now; `main` for its
#                                     newest), refresh the test cases, run gen
#
# Needs cargo (rustup fetches the pinned toolchain), curl and git. GITHUB_TOKEN is used for the GitHub API if it is set.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tool="$root/native/protocol"
manifest="$tool/Cargo.toml"
generated="$root/app/src/main/java/live/hanab/client/protocol/Protocol.kt"
fixtures="$root/app/src/test/resources/protocol/compatibility.json"
repo="Hanabi-Live/hanabi-live"
# The protocol is in server/protocol; the website's generated TypeScript for it is checked in beside the clients, and the frames
# around the payloads (frames.ts, written by hand) are what net/WireFrames.kt follows.
paths=(server/protocol packages/data/src/generated packages/game/src/generated packages/data/src/functions/frames.ts)

pin() {
  local rev
  rev="$(grep hanabi_protocol "$manifest" | sed -n 's/.*rev = "\([0-9a-f]*\)".*/\1/p' | head -n1)"
  [ -n "$rev" ] || { echo "No rev for hanabi_protocol in $manifest" >&2; exit 1; }
  echo "$rev"
}

generate() { (cd "$tool" && cargo run --quiet -- "$1"); }

github() {
  local args=(-fsSL -H "Accept: application/vnd.github+json" -H "User-Agent: hanab-android")
  [ -n "${GITHUB_TOKEN:-}" ] && args+=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl "${args[@]}" "https://api.github.com/repos/$repo/$1"
}

upstream_head() { git ls-remote "https://github.com/$repo" HEAD | cut -f1; }

# The commit the website is running: its client's script is named for it (that is the version the server checks).
deployed_rev() {
  local rev
  rev="$(curl -fsSL https://hanab.live/ | grep -o '/public/js/bundles/main\.[0-9a-f]\{40\}\.min\.js' | head -n1 | sed 's/.*main\.\([0-9a-f]*\)\.min\.js/\1/')"
  [ -n "$rev" ] || { echo "Could not read the website's version" >&2; exit 1; }
  echo "$rev"
}

case "${1:-}" in
  gen)
    mkdir -p "$(dirname "$generated")"
    generate "$generated"
    echo "Wrote $generated (pinned to $(pin))"
    ;;
  check)
    temp="$(mktemp)"
    trap 'rm -f "$temp"' EXIT
    generate "$temp"
    if ! cmp -s "$temp" "$generated"; then
      echo "Protocol.kt is not what the generator writes for the pinned commit. Run scripts/protocol.sh gen." >&2
      exit 1
    fi
    echo "Protocol.kt matches the pinned commit $(pin)."
    ;;
  outdated)
    pinned="$(pin)"
    head="$(deployed_rev)"
    if [ "$pinned" = "$head" ]; then
      echo "The protocol of the website that is live ($head) has not changed since the pinned commit $pinned."
      exit 0
    fi
    # The files of the protocol that changed between the two commits (the API answers in a single line of JSON).
    changed="$(github "compare/$pinned...$head" | grep -o '"filename":"[^"]*"' | sed 's/"filename":"\(.*\)"/\1/' |
      while read -r file; do for p in "${paths[@]}"; do case "$file" in "$p"*) echo "$file"; break ;; esac; done; done)"
    if [ -z "$changed" ]; then
      echo "The protocol of the website that is live ($head) has not changed since the pinned commit $pinned."
      exit 0
    fi
    echo "The website that is live ($head) has a different protocol from the pinned commit ${pinned}:"
    # The commits between the pin and the live site, by their first eight characters (a commit starts at its own "sha"; a
    # file, a tree and a parent have one too, but no node_id beside it).
    between="$(github "compare/$pinned...$head" | sed 's/{"sha":"\([0-9a-f]\{8\}\)[0-9a-f]\{32\}","node_id"/\n@\1@/g' | sed -n 's/^@\([0-9a-f]\{8\}\)@.*/\1/p')"
    for p in "${paths[@]}"; do
      github "commits?path=$p&sha=$head&per_page=100" | sed 's/{"sha":"\([0-9a-f]\{40\}\)","node_id"/\n\1 /g' |
        sed -n 's/^\([0-9a-f]\{8\}\)[0-9a-f]\{32\} .*"message":"\([^"\\]*\).*/\1 \2/p'
    done | sort -u | while read -r short message; do
      # The list has every commit that ever touched the path; only the ones since the pin matter.
      case "$between" in *"$short"*) echo "  $short $message" ;; esac
    done
    echo "$(echo "$changed" | wc -l | tr -d ' ') file(s): $(echo "$changed" | paste -sd, - | sed 's/,/, /g')"
    echo "Run scripts/protocol.sh bump, fix what the build and the tests then show, and commit the result."
    exit 1
    ;;
  bump)
    rev="${2:-}"
    if [ -z "$rev" ]; then rev="$(deployed_rev)"; elif [ "$rev" = main ]; then rev="$(upstream_head)"; fi
    rev="$(github "commits/$rev" | grep -o '"sha":"[0-9a-f]\{40\}"' | head -n1 | sed 's/"sha":"\(.*\)"/\1/')"
    [ -n "$rev" ] || { echo "Could not find that commit" >&2; exit 1; }
    # sed -i differs between GNU and BSD; write through a temp file.
    sed "/hanabi_protocol/s/rev = \"[0-9a-f]*\"/rev = \"$rev\"/" "$manifest" > "$manifest.new" && mv "$manifest.new" "$manifest"
    (cd "$tool" && cargo update --quiet --package hanabi_protocol)
    mkdir -p "$(dirname "$fixtures")"
    curl -fsSL "https://raw.githubusercontent.com/$repo/$rev/server/protocol/fixtures/compatibility.json" -o "$fixtures"
    generate "$generated"
    echo "Pinned to $rev. Build and run the tests (./gradlew testDebugUnitTest): what fails is what changed."
    git -C "$root" diff --stat
    ;;
  *)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
