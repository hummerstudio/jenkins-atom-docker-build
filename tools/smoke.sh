#!/usr/bin/env bash
# A fake docker CLI records the argv: images, tags, build args, and that a password never appears in argv.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

work="$(mktemp -d)"; mkdir -p "$work/bin"
cat >"$work/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$SMOKE_ARGV"
case "$1" in
  version) echo "27.0.3" ;;
  build)   echo "Successfully built sha256:abc123" ;;
  image)   echo "sha256:abc123def456" ;;
  login)   cat > "$SMOKE_STDIN" ;;   # the password arrives here, not in argv
  push)    echo "pushed $2" ;;
esac
exit 0
SH
chmod +x "$work/bin/docker"
export SMOKE_ARGV="$work/argv.txt" SMOKE_STDIN="$work/stdin.txt"

# 1. build with two tags and one build-arg
dir="$work/build"; mkdir -p "$dir"
printf '{"image": "registry.example.com/parcel", "tags": "1.4.0, latest", "buildArgs": "VERSION=1.4.0",\n "context": ".", "dockerBinary": "%s/bin/docker"}' "$work" >"$dir/input.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "build run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success: $(cat "$dir/output.json")"
[ "$(output "$dir" imageId)" = "sha256:abc123def456" ] || fail "image id: $(cat "$dir/output.json")"
[ "$(output "$dir" tags)" = "registry.example.com/parcel:1.4.0,registry.example.com/parcel:latest" ] || fail "tags: $(cat "$dir/output.json")"
grep -q -- "--tag registry.example.com/parcel:1.4.0" "$work/argv.txt" || fail "first tag missing"
grep -q -- "--build-arg VERSION=1.4.0" "$work/argv.txt" || fail "build arg missing"
grep -q "push" "$work/argv.txt" && fail "push=false must not push"

# 2. push with registry credentials: the password goes to stdin, never argv
dir="$work/push"; mkdir -p "$dir"
printf '{"image": "registry.example.com/parcel", "tags": "latest", "push": "true",\n "dockerBinary": "%s/bin/docker"}' "$work" >"$dir/input.json"
printf '{"registryUser": "robot", "registryPassword": "hunter2"}' >"$dir/.secrets.json"
run "$dir" >/dev/null 2>&1 || fail "push run failed: $(cat "$dir/output.json")"
grep -q "push registry.example.com/parcel:latest" "$work/argv.txt" || fail "push missing"
grep -q -- "login registry.example.com" "$work/argv.txt" || fail "login missing"
grep -q "hunter2" "$work/argv.txt" && fail "the password must not be in argv"
grep -q "hunter2" "$work/stdin.txt" || fail "the password must reach docker through stdin"
grep -q "hunter2" "$dir/output.json" && fail "the password must not be in the outputs"

# 3. no docker on the agent is a clear environment error (a path that does not exist, not a PATH trick)
dir="$work/nodocker"; mkdir -p "$dir"
printf '{"image": "x/y", "dockerBinary": "%s/no-such-docker"}' "$work" >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "missing docker must fail"
grep -q "docker CLI is not available" "$dir/output.json" || fail "message: $(cat "$dir/output.json")"

echo "smoke ok: tags/build-args, push, password via stdin, missing docker"
