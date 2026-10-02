#!/usr/bin/env bash
# Hot Death Uno - local Jenkins client
#
# Companion to the Jenkinsfile. Both this and jenkins.ps1 used to be untracked,
# on the grounds that a client holding a controller URL is not part of the
# project's build; neither is any more, and both have been committed for some
# time. What is untracked is the credentials -- jenkins-creds and jenkins-creds.xml
# are in .gitignore, and that is the part that matters.
#
# The job declares two parameters. RUN_TESTS: off means build and sign only,
# with no unit tests, no lint and no emulator matrix. SCREENSHOTS: on adds a
# stage that boots an emulator and captures the README images from the release
# APK. --tests/--no-tests and --screenshots/--no-screenshots set them; with
# neither, the job's own defaults apply. This posts to buildWithParameters
# rather than /build, because a parameterized job rejects a bare POST to /build
# with "HTTP 400 Nothing is submitted".
#
# Config, highest precedence first:
#   1. environment (JENKINS_URL, JENKINS_USER, JENKINS_TOKEN, JENKINS_JOB)
#   2. ./jenkins-creds, one KEY=VALUE per line
#   3. built-in defaults below
#
# The token is a Jenkins API token, not a password. Create one under
# your-user / Configure / API Token.

set -euo pipefail

# Left empty on purpose: load_creds treats an already-set value as "environment
# wins", so applying the defaults here would make the creds file unable to set
# either of them. The real defaults are applied in main, after load_creds.
JENKINS_URL="${JENKINS_URL:-}"
JENKINS_JOB="${JENKINS_JOB:-}"
JENKINS_USER="${JENKINS_USER:-}"
JENKINS_TOKEN="${JENKINS_TOKEN:-}"
CREDS_FILE="${JENKINS_CREDS_FILE:-$(dirname "$0")/jenkins-creds}"

# The matrix boots four emulators in sequence against an 8 GB controller, so a
# full run is long. Cap the wait well above a worst case rather than hanging
# forever, but not so tight that a slow first-time SDK download trips it.
DEFAULT_TIMEOUT=10800
POLL_INTERVAL=5

# Exit codes. Distinct so CI wrappers can tell "the build broke" from "I could
# not talk to Jenkins".
EX_OK=0
EX_BUILD=1
EX_USAGE=2
EX_TIMEOUT=3
EX_HTTP=4

# Response state, refreshed by req().
body=
http_code=
location=
CRUMB_FIELD=
CRUMB_VALUE=

TMPDIR_HELPER=
trap '[ -n "${TMPDIR_HELPER:-}" ] && rm -f "${TMPDIR_HELPER}"/'* EXIT

# die <message...> [exit-code]
# The code is optional and, when present, is the last argument. It has to be
# pulled off before printing, or `$*` would splice the number into the message.
die() {
  local code=$EX_USAGE
  case "${!#}" in
    ''|*[!0-9]*) ;;
    *) code=${!#}; set -- "${@:1:$#-1}" ;;
  esac
  printf '%s: %s\n' "$PROG" "$*" >&2
  exit "$code"
}
info() { printf '%s\n' "$*"; }
# Progress output. Goes to stderr because a function like queue_build has its
# stdout captured as a return value, and a stray line there corrupts it.
note() { printf '%s\n' "$*" >&2; }

usage() {
  cat <<EOF
usage: ${PROG##*/} [options] [command]

commands:
  build      queue a build, then follow it to completion (default)
  status     report the last build without queueing anything
  log        print the console output of the last build

options:
  -n, --no-watch    queue a build and return its number immediately
      --force       queue even if a build is already running
      --timeout N   seconds to wait for completion (default ${DEFAULT_TIMEOUT})
      --tests       run unit tests, lint and the API 34-36 emulator matrix
      --no-tests    build and sign only: skip every test stage
                    (default: whatever the job is configured to do)
      --screenshots
                    boot an emulator and capture the four README screenshots
                    from the release APK
      --no-screenshots
                    skip that stage
                    (default: whatever the job is configured to do)
  -h, --help        this text
EOF
}

load_creds() {
  [ -f "$CREDS_FILE" ] || return 0
  local line key val
  # Parsed, not sourced: a creds file that gets executed rather than read is a
  # code-execution hazard the moment it is ever copied or synced somewhere.
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key=${line%%=*}
    val=${line#*=}
    # Trim, then unquote: `KEY = "value"` is a natural way to write these and
    # the stray space would otherwise end up inside the URL or the token.
    val=${val#"${val%%[![:space:]]*}"}
    val=${val%"${val##*[![:space:]]}"}
    val=${val#\"}; val=${val%\"}
    val=${val#\'}; val=${val%\'}
    key=${key//[[:space:]]/}
    # Environment wins, so only fill in what is still unset. ${VAR:-} rather
    # than $VAR: this runs under `set -u`, where a bare unset reference aborts.
    case "$key" in
      JENKINS_URL)   [ -n "${JENKINS_URL:-}" ]   || JENKINS_URL=$val ;;
      JENKINS_USER)  [ -n "${JENKINS_USER:-}" ]  || JENKINS_USER=$val ;;
      JENKINS_TOKEN) [ -n "${JENKINS_TOKEN:-}" ] || JENKINS_TOKEN=$val ;;
      JENKINS_JOB)   [ -n "${JENKINS_JOB:-}" ]   || JENKINS_JOB=$val ;;
    esac
  done < "$CREDS_FILE"
}

# No jq on this box, so JSON is picked apart with sed. Jenkins' pipeline API
# returns small flat objects, which is the easy case for this.
json_field() { # json_field <field>  -- scalar, from the last response
  printf '%s' "$body" | tr ',' '\n' | grep -m1 "\"$1\"[[:space:]]*:" \
    | sed "s/^.*\"$1\"[[:space:]]*:[[:space:]]*//" || true
}
json_value() {
  # Trailing structure has to go too: splitting on commas leaves the last member
  # of an object as '"url":".../"}', and stripping quotes alone yields a value
  # with a stray } on the end.
  json_field "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
                           -e 's/[]},]*$//' -e 's/^"//' -e 's/"$//'
}

# req <method> <path> [data]
req() {
  local method=$1 path=$2 data=${3-} hdr out
  TMPDIR_HELPER=$(mktemp -d)
  hdr=$TMPDIR_HELPER/headers
  out=$TMPDIR_HELPER/body

  local -a args=(
    -sS -D "$hdr" -o "$out" -w '%{http_code}'
    -X "$method" --user "$JENKINS_USER:$JENKINS_TOKEN"
    "$JENKINS_URL$path"
  )
  [ -n "$CRUMB_FIELD" ] && args+=(-H "${CRUMB_FIELD}: ${CRUMB_VALUE}")
  [ -n "$data" ] && args+=(--data "$data")

  # Not `curl ... || printf 000`: -w already wrote its own code before the
  # failure, so that would concatenate into something like 000000.
  if ! http_code=$(curl "${args[@]}" 2>/dev/null); then
    http_code=000
  fi
  body=$(cat "$out" 2>/dev/null || true)
  location=$(tr -d '\r' < "$hdr" 2>/dev/null | sed -n 's/^[Ll]ocation:[[:space:]]*//p' | head -n1 || true)
  rm -rf "$TMPDIR_HELPER"
  TMPDIR_HELPER=
}

# CSRF. The field name comes back from the server rather than being assumed to
# be Jenkins-Crumb, which is the whole reason to ask crumbIssuer instead of
# hardcoding the header.
fetch_crumb() {
  req GET /crumbIssuer/api/json
  [ "$http_code" = 200 ] || return 0
  CRUMB_FIELD=$(json_value crumbRequestField)
  CRUMB_VALUE=$(json_value crumb)
}

check_auth() {
  req GET "/api/json"
  case "$http_code" in
    200) return 0 ;;
    401|403) die "auth failed for user '${JENKINS_USER}' - check the API token" $EX_HTTP ;;
    *) die "cannot reach ${JENKINS_URL} (HTTP ${http_code})" $EX_HTTP ;;
  esac
}

# Queue a build and echo its number. Jenkins answers 201 with the queue item,
# not the build, so this waits out the queue first.
# queue_build <last-build-number> [name=value ...]
queue_build() {
  local before=${1-} item build_url exec_obj num waited=0
  shift || true
  # buildWithParameters, always. A job that declares a parameter refuses a bare
  # POST to /build, and one that declares none accepts this just the same, so
  # there is no need to detect which kind this is. With no arguments the job
  # applies its own defaults.
  local data=''
  local kv
  for kv in "$@"; do
    data="${data}${data:+&}${kv}"
  done
  req POST "/job/${JENKINS_JOB}/buildWithParameters" "$data"
  case "$http_code" in
    201) ;;
    404) die "job '${JENKINS_JOB}' does not exist on ${JENKINS_URL}" $EX_HTTP ;;
    403) die "no permission to build '${JENKINS_JOB}'" $EX_HTTP ;;
    *) die "queueing failed (HTTP ${http_code}): $(printf '%s' "$body" | tr -d '\n' | cut -c1-200)" $EX_HTTP ;;
  esac

  item=$location
  [ -n "$item" ] || die "no queue item in the response" $EX_HTTP
  # Keep only the path. The Location is built from Jenkins' own configured
  # Jenkins URL, which need not match the one we dialled -- a reverse proxy or
  # localhost-vs-127.0.0.1 is enough to make it differ -- and the queue is
  # re-polled against our own JENKINS_URL anyway. A relative Location passes
  # through untouched.
  item=$(printf '%s' "$item" | sed 's#^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*##')
  case "$item" in
    /*) ;;
    *) die "unexpected queue location: $location" $EX_HTTP ;;
  esac
  note "queued, waiting for an executor..."

  while :; do
    req GET "${item}api/json"
    [ "$http_code" = 200 ] || die "queue lookup failed (HTTP ${http_code})" $EX_HTTP
    # The executable object holds the build number. No nested braces here, so
    # [^}]* is a safe way to stop at its end.
    exec_obj=$(printf '%s' "$body" | sed -n 's/.*"executable":[[:space:]]*{\([^}]*\)}.*/\1/p')
    if [ -n "$exec_obj" ]; then
      num=$(printf '%s' "$exec_obj" | sed -n 's/.*"number":[[:space:]]*\([0-9][0-9]*\).*/\1/p')
      [ -n "$num" ] || die "could not read the build number from the queue item" $EX_HTTP
      # Guards against grabbing a build that was already running: ours has to be
      # a higher number than whatever was last there.
      if [ -n "$before" ] && [ "$num" -le "$before" ]; then
        die "queue returned build #${num}, which is not newer than #${before}" $EX_HTTP
      fi
      echo "$num"
      return 0
    fi
    waited=$((waited + POLL_INTERVAL))
    [ "$waited" -lt "$DEFAULT_TIMEOUT" ] || die "timed out waiting in the queue" $EX_TIMEOUT
    sleep "$POLL_INTERVAL"
  done
}

build_state() { # echoes "<building:true|false> <result:...|null>"
  req GET "/job/${JENKINS_JOB}/$1/api/json"
  [ "$http_code" = 200 ] || die "cannot read build #$1 (HTTP ${http_code})" $EX_HTTP
  local b r
  b=$(json_value building); r=$(json_value result)
  [ -n "$b" ] || b=false
  [ -n "$r" ] || r=null
  printf '%s %s\n' "$b" "$r"
}

# Print only the bytes of the console log we have not shown yet. consoleText is
# served whole, so track how much has already been emitted rather than diffing.
stream_console() {
  local num=$1 offset=0 size tmp
  tmp=$(mktemp)
  while :; do
    if curl -sS --user "$JENKINS_USER:$JENKINS_TOKEN" \
         -o "$tmp" "$JENKINS_URL/job/${JENKINS_JOB}/${num}/consoleText"; then
      size=$(wc -c < "$tmp" | tr -d ' ')
      if [ "$size" -gt "$offset" ]; then
        tail -c "+$((offset + 1))" "$tmp"
        offset=$size
      fi
    fi
    local state
    state=$(build_state "$num")
    case "$state" in
      "true "*) sleep "$POLL_INTERVAL" ;;
      *) rm -f "$tmp"; return 0 ;;
    esac
  done
}

wait_for_build() {
  local num=$1 deadline=$((SECONDS + $2)) state result
  while :; do
    state=$(build_state "$num")
    case "$state" in
      "true "*)
        [ "$SECONDS" -lt "$deadline" ] || die "build #${num} still running after ${2}s" $EX_TIMEOUT
        ;;
      *)
        result=${state#* }
        return 0
        ;;
    esac
    sleep "$POLL_INTERVAL"
  done
}

cmd_status() {
  req GET "/job/${JENKINS_JOB}/lastBuild/api/json"
  case "$http_code" in
    404) die "job '${JENKINS_JOB}' has never been built" $EX_HTTP ;;
    200) ;;
    *) die "cannot read the last build (HTTP ${http_code})" $EX_HTTP ;;
  esac
  local num result building url when
  num=$(json_value number)
  building=$(json_value building)
  result=$(json_value result)
  url=$(json_value url)
  when=$(json_value timestamp)
  info "job      ${JENKINS_JOB}"
  info "build    #${num}"
  info "state    $([ "$building" = true ] && echo 'RUNNING' || echo "${result:-UNKNOWN}")"
  [ -n "${when:-}" ] && [ "$when" != null ] && info "started  $(date -d "@$((when / 1000))" 2>/dev/null || echo "$when")"
  [ -n "${url:-}" ] && info "url      ${url}"
}

cmd_log() {
  req GET "/job/${JENKINS_JOB}/lastBuild/api/json"
  [ "$http_code" = 200 ] || die "no builds to read a log from (HTTP ${http_code})" $EX_HTTP
  local num
  num=$(json_value number)
  info "--- console output for #${num} ---"
  req GET "/job/${JENKINS_JOB}/${num}/consoleText"
  [ "$http_code" = 200 ] || die "cannot read the console log (HTTP ${http_code})" $EX_HTTP
  printf '%s' "$body"
}

cmd_build() {
  local force=$1 watch=$2 timeout=$3 tests=$4 before='' state
  shift 4
  local -a params=("$@")

  if [ "$watch" = 1 ]; then
    req GET "/job/${JENKINS_JOB}/lastBuild/api/json"
    if [ "$http_code" = 200 ]; then
      before=$(json_value number)
      state=$(json_value building)
      if [ "$state" = true ] && [ "$force" = 0 ]; then
        die "#${before} is still running; re-run with --force to queue behind it" $EX_USAGE
      fi
    fi
  fi

  local num
  num=$(queue_build "$before" ${params+"${params[@]}"})
  info "build #${num} queued: ${JENKINS_URL}/job/${JENKINS_JOB}/${num}/"

  [ "$watch" = 1 ] || return "$EX_OK"

  stream_console "$num"
  wait_for_build "$num" "$timeout"

  req GET "/job/${JENKINS_JOB}/${num}/api/json"
  local result
  result=$(json_value result)
  case "$result" in
    SUCCESS)
      info "build #${num}: SUCCESS"
      return "$EX_OK"
      ;;
    UNSTABLE|ABORTED)
      info "build #${num}: ${result}"
      return "$EX_BUILD"
      ;;
    *)
      info "build #${num}: ${result:-FAILED}"
      info "console: ${JENKINS_URL}/job/${JENKINS_JOB}/${num}/console"
      return "$EX_BUILD"
      ;;
  esac
}

main() {
  local force=0 watch=1 timeout=$DEFAULT_TIMEOUT cmd=build tests=auto
  local screenshots=auto
  local -a params=()
  while [ $# -gt 0 ]; do
    case "$1" in
      build|status|log) cmd=$1; shift ;;
      -n|--no-watch) watch=0; shift ;;
      --force) force=1; shift ;;
      --tests) tests=yes; shift ;;
      --no-tests) tests=no; shift ;;
      --screenshots) screenshots=yes; shift ;;
      --no-screenshots) screenshots=no; shift ;;
      --timeout) [ $# -ge 2 ] || die "--timeout needs a value" $EX_USAGE
                 timeout=$2; shift 2 ;;
      --timeout=*) timeout=${1#*=}; shift ;;
      -h|--help) usage; exit "$EX_OK" ;;
      *) usage >&2; die "unknown argument: $1" $EX_USAGE ;;
    esac
  done

  load_creds

  # Only sent when asked for, so the default really is the job's own. Order
  # matches the job's parameterDefinitions, which is not a requirement but
  # makes a queue in the UI read in the order the job declares them.
  #
  # Tri-state for both, as RUN_TESTS was. jenkins.ps1 only has -Screenshots with
  # no -NoScreenshots, which is enough there because the job's own default is
  # false; this side sends an explicit false as well, so a build can turn the
  # stage off on a controller whose default has been changed.
  case "$tests" in
    yes) params+=("RUN_TESTS=true") ;;
    no)  params+=("RUN_TESTS=false") ;;
  esac
  case "$screenshots" in
    yes) params+=("SCREENSHOTS=true") ;;
    no)  params+=("SCREENSHOTS=false") ;;
  esac

  # Precedence, lowest last: env, then the creds file, then these.
  JENKINS_URL=${JENKINS_URL:-https://jenkins.smccloud.com}
  JENKINS_JOB=${JENKINS_JOB:-Hot-Death-Uno}
  JENKINS_URL=${JENKINS_URL%/}

  [ -n "$JENKINS_USER" ] || die "no JENKINS_USER set (env, or $CREDS_FILE)" $EX_USAGE
  [ -n "$JENKINS_TOKEN" ] || die "no JENKINS_TOKEN set (env, or $CREDS_FILE)" $EX_USAGE

  # Checked here rather than at the arithmetic in wait_for_build, where a
  # non-numeric value would fail as a syntax error with no context.
  case "$timeout" in
    ''|*[!0-9]*) die "--timeout needs a positive number of seconds, got '$timeout'" $EX_USAGE ;;
  esac
  [ "$timeout" -gt 0 ] || die "--timeout must be greater than zero" $EX_USAGE

  check_auth
  fetch_crumb

  case "$cmd" in
    build) cmd_build "$force" "$watch" "$timeout" "$tests" \
             ${params+"${params[@]}"} ;;
    status) cmd_status ;;
    log) cmd_log ;;
  esac
}

PROG=${0##*/}
main "$@"
