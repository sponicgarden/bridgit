#!/usr/bin/env bash
# Publish a Bridgit release build for the in-app updater (spec 12.14, D21, UPD-01).
#
# Usage:
#   tools/publish-apk.sh --notes "What changed, in one or two plain sentences" [options]
#
# Options:
#   --notes TEXT     Release notes shown in Bridgit's update dialog and on the GitHub release (required).
#   --apk PATH       Publish this APK instead of building one.
#   --no-build       Don't run ./gradlew assembleRelease; use app/build/outputs/apk/release/app-release.apk.
#   --no-github      Skip `gh release create`.
#   --dry-run        Do every check and write latest.json, but upload nothing and create no release.
#   --force          Publish even if the public latest.json already has this or a higher version_code.
#   -h, --help       Show this help.
#
# What it does:
#   1. Builds the release APK (unless --apk / --no-build).
#   2. Reads package, versionName and versionCode with `aapt2 dump badging`; refuses anything but
#      net.finleg.bridget (so a .debug build can never be published).
#   3. Checks the signing certificate with `apksigner verify --print-certs` against Bridgit's release
#      key (SHA-256 6bbd482d…2f61).
#   4. Computes SHA-256 and size and writes latest.json:
#      {"package","version_name","version_code","url","sha256","size_bytes","published_at","notes"}.
#   5. Uploads bridget/bridget-<version>.apk and API docs, verifies the APK, then bridget/latest.json, to the public R2 bucket
#      via the Cloudflare API, and checks each reply says "success": true.
#   5a. Generates the public API page (ApiDocsTest writes app/build/apidocs/api.html for this version)
#      and uploads it as bridget/api.html; the What can Bridgit do? cards' Share buttons link to it.
#   6. Downloads both back from the public host and checks the SHA-256.
#   7. Creates the GitHub release bridget-v<version> with the APK attached (unless --no-github).
#
# Publisher settings (public host, R2 account and bucket, vault item) come from tools/publish.env, which is
# gitignored; copy tools/publish.env.example and fill it in. Environment variables of the same name win.
#
# The Cloudflare R2 token comes from the Bitwarden item BRIDGIT_R2_TOKEN_ITEM (login password, or a field
# whose name contains "token"), using an active bwaccesser grant or BW_SESSION. The token is
# written straight from Bitwarden's JSON into a 0600 header file that curl reads with -H @file; it
# is never printed, never in a shell variable and never on a command line. The file is deleted on exit.
# --dry-run doesn't touch Bitwarden at all.

set -euo pipefail

PACKAGE="net.finleg.bridget"
EXPECTED_CERT="6bbd482d18efdc58ca28de8358405602ebb0405b479cabd36b37ff8242042f61"
UA="bridget-publish/1"
MAX_BYTES=$((100 * 1024 * 1024))

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
[[ -f "${ROOT}/tools/publish.env" ]] && source "${ROOT}/tools/publish.env"
PUBLIC_HOST="${BRIDGIT_PUBLIC_HOST:-}"
PUBLIC_BASE="https://${PUBLIC_HOST}/bridget"
R2_ACCOUNT="${BRIDGIT_R2_ACCOUNT:-}"
R2_BUCKET="${BRIDGIT_R2_BUCKET:-}"
R2_API="https://api.cloudflare.com/client/v4/accounts/${R2_ACCOUNT}/r2/buckets/${R2_BUCKET}/objects"
BW_ITEM="${BRIDGIT_R2_TOKEN_ITEM:-}"
GH_REPO="${BRIDGIT_GH_REPO:-sponicgarden/bridgit}"
NOTES=""
APK=""
BUILD=1
GITHUB=1
DRY_RUN=0
FORCE=0

usage() { sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }
die() { echo "publish-apk: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --notes) [[ $# -ge 2 ]] || die "--notes needs a value"; NOTES="$2"; shift 2 ;;
    --apk) [[ $# -ge 2 ]] || die "--apk needs a path"; APK="$2"; BUILD=0; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --no-github) GITHUB=0; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done
[[ -n "${NOTES// /}" ]] || die "--notes is required"
for v in BRIDGIT_PUBLIC_HOST BRIDGIT_R2_ACCOUNT BRIDGIT_R2_BUCKET BRIDGIT_R2_TOKEN_ITEM; do
  [[ -n "${!v:-}" ]] || die "${v} is not set (tools/publish.env; see tools/publish.env.example)"
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/bridget-publish.XXXXXX")"
HDR=""
cleanup() { [[ -n "${HDR}" ]] && rm -f "${HDR}"; rm -rf "${WORK}"; }
trap cleanup EXIT INT TERM

# ---- Tools ----
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${SDK}" && -f "${ROOT}/local.properties" ]]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "${ROOT}/local.properties" | head -1)"
fi
[[ -n "${SDK}" && -d "${SDK}/build-tools" ]] || die "Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)"
BT="$(ls -d "${SDK}"/build-tools/*/ | sort -V | tail -1)"
AAPT2="${BT}aapt2"; APKSIGNER="${BT}apksigner"
[[ -x "${AAPT2}" && -x "${APKSIGNER}" ]] || die "aapt2/apksigner not found in ${BT}"
command -v python3 >/dev/null || die "python3 not found"
command -v shasum >/dev/null || die "shasum not found"
if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi

# ---- 1. Build ----
if [[ ${BUILD} -eq 1 ]]; then
  [[ -f "${ROOT}/keystore.properties" ]] || die "keystore.properties missing: the release build would be unsigned"
  echo "==> Building the release APK"
  (cd "${ROOT}" && ./gradlew assembleRelease -q)
fi
[[ -n "${APK}" ]] || APK="${ROOT}/app/build/outputs/apk/release/app-release.apk"
[[ -f "${APK}" ]] || die "APK not found: ${APK}"

# ---- 2. Package and version ----
BADGING="$("${AAPT2}" dump badging "${APK}" 2>/dev/null | sed -n 1p || true)"
read -r PKG VCODE VNAME < <(python3 -c '
import re, sys
line = sys.argv[1]
def f(k):
    m = re.search(k + r"=\x27([^\x27]*)\x27", line)
    return m.group(1) if m else ""
print(f("name"), f("versionCode"), f("versionName"))
' "${BADGING}")
[[ "${PKG}" == "${PACKAGE}" ]] || die "package is '${PKG}', expected ${PACKAGE} (debug builds can't be published)"
[[ "${VCODE}" =~ ^[1-9][0-9]*$ ]] || die "bad versionCode '${VCODE}'"
[[ "${VNAME}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "bad versionName '${VNAME}'"
echo "==> ${PKG} ${VNAME} (versionCode ${VCODE})"

# The supplied APK must come from this exact clean revision, even with --apk/--no-build.
[[ -z "$(git -C "${ROOT}" status --porcelain)" ]] || die "source tree is dirty; commit the reviewed changes before publishing"
COMMIT="$(git -C "${ROOT}" rev-parse HEAD)"
python3 "${ROOT}/tools/check-apk-provenance.py" "${APK}" "${COMMIT}" || die "APK provenance check failed"
git -C "${ROOT}" fetch origin main || die "cannot verify the production revision"
git -C "${ROOT}" merge-base --is-ancestor "${COMMIT}" origin/main || die "commit ${COMMIT} must be on origin/main before publication"
if [[ ${GITHUB} -eq 1 ]]; then
  command -v gh >/dev/null || die "gh not found (or pass --no-github)"
  [[ "$(gh api "repos/${GH_REPO}" --jq '.permissions.push')" == "true" ]] || die "GitHub write access could not be verified"
  [[ -z "$(git -C "${ROOT}" ls-remote --tags origin "refs/tags/bridget-v${VNAME}")" ]] || die "release tag already exists"
  gh release view "bridget-v${VNAME}" --repo "${GH_REPO}" >/dev/null 2>&1 && die "GitHub release bridget-v${VNAME} already exists"
fi

# ---- 3. Signing certificate ----
CERTS="$("${APKSIGNER}" verify --print-certs "${APK}" 2>/dev/null | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p' | tr "A-F" "a-f" | sort -u || true)"
[[ -n "${CERTS}" ]] || die "apksigner found no signer (unsigned APK?)"
[[ "${CERTS}" == "${EXPECTED_CERT}" ]] || die "signing certificate is not Bridgit's release key: ${CERTS//$'\n'/, }"
echo "==> Signed with the release key (${EXPECTED_CERT:0:8}…${EXPECTED_CERT: -4})"

# ---- 4. Hash, size, latest.json ----
SHA="$(shasum -a 256 "${APK}" | awk '{print $1}')"
SIZE="$(wc -c < "${APK}" | tr -d ' ')"
(( SIZE > 0 && SIZE <= MAX_BYTES )) || die "APK size ${SIZE} bytes is outside 1 byte .. 100 MB"
KEY_APK="bridget/bridget-${VNAME}.apk"
KEY_JSON="bridget/latest.json"
URL="${PUBLIC_BASE}/bridget-${VNAME}.apk"
MANIFEST="${WORK}/latest.json"
python3 - "${MANIFEST}" "${PKG}" "${VNAME}" "${VCODE}" "${URL}" "${SHA}" "${SIZE}" "${NOTES}" <<'PY'
import json, sys, datetime
out, pkg, name, code, url, sha, size, notes = sys.argv[1:]
m = {
    "package": pkg, "version_name": name, "version_code": int(code), "url": url,
    "sha256": sha, "size_bytes": int(size),
    "published_at": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "notes": notes.strip(),
}
with open(out, "w") as f:
    json.dump(m, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY
echo "==> latest.json"
cat "${MANIFEST}"

# Refuse to publish a version that isn't newer than what's already public.
curl -fsS -A "${UA}" --max-time 20 "${PUBLIC_BASE}/latest.json" -o "${WORK}/current.json" || die "cannot verify the current public release"
CUR_CODE="$(python3 - "${WORK}/current.json" "${PACKAGE}" <<'CHECK'
import json, sys
m = json.load(open(sys.argv[1]))
assert m.get("package") == sys.argv[2], "unexpected package in current release"
code = m["version_code"]
assert type(code) is int and code > 0, "invalid current version_code"
print(code)
CHECK
)" || die "current public release manifest is invalid"
if (( CUR_CODE >= VCODE )); then
  if [[ ${FORCE} -eq 1 ]]; then echo "==> Public latest.json has version_code ${CUR_CODE}; publishing ${VCODE} anyway (--force)"
  else die "public latest.json already has version_code ${CUR_CODE} (this APK is ${VCODE}); bump versionCode or pass --force"; fi
else
  echo "==> Public latest.json has version_code ${CUR_CODE}; this is ${VCODE}"
fi

# ---- 5a. The public API page ----
echo "==> Generating the API page for ${VNAME}"
DOCS="${ROOT}/app/build/apidocs/api.html"
rm -f "${DOCS}"
(cd "${ROOT}" && ./gradlew testDebugUnitTest --tests '*ApiDocsTest*' --rerun -PbridgetDocsVersion="${VNAME}" -q) || die "ApiDocsTest failed"
grep -q "version ${VNAME}," "${DOCS}" || die "api.html wasn't written for ${VNAME}"

if [[ ${DRY_RUN} -eq 1 ]]; then
  echo "==> Dry run: would PUT ${KEY_APK} (${SIZE} bytes, application/vnd.android.package-archive)"
  echo "==> Dry run: would PUT ${KEY_JSON} (application/json)"
  echo "==> Dry run: would PUT bridget/api.html (text/html)"
  echo "==> Dry run: would verify ${URL} and ${PUBLIC_BASE}/latest.json against SHA-256 ${SHA}"
  [[ ${GITHUB} -eq 1 ]] && echo "==> Dry run: would create GitHub release bridget-v${VNAME} on ${GH_REPO}"
  echo "==> Dry run finished; nothing was uploaded."
  exit 0
fi

# ---- 5. Upload ----
# Reuse existing authorization; publishing must never read the master password or renew a grant.
if [[ -x "${HOME}/bin/bwaccesser" ]]; then
  "${HOME}/bin/bwaccesser" status >/dev/null || die "An active bwaccesser grant is required"
  BW_READ=("${HOME}/bin/bwaccesser" bw)
else
  command -v bw >/dev/null || die "bw (Bitwarden CLI) not found"
  [[ -n ${BW_SESSION:-} ]] || die "Provide an already-unlocked BW_SESSION"
  BW_READ=(bw)
fi
HDR="$(mktemp "${WORK}/hdr.XXXXXX")"
chmod 600 "${HDR}"
"${BW_READ[@]}" get item "${BW_ITEM}" | python3 -c '
import json, sys
d = json.loads(sys.stdin.read(), strict=False)
tok = (d.get("login") or {}).get("password") or next(
    (f.get("value") for f in d.get("fields") or [] if "token" in (f.get("name") or "").lower() and f.get("value")), "")
if not tok:
    sys.exit("no token in the Bitwarden item")
with open(sys.argv[1], "w") as f:
    f.write("Authorization: Bearer " + tok.strip() + "\n")
' "${HDR}"

upload() { # key, file, content-type
  local resp="${WORK}/resp.json"
  curl --retry 3 --retry-all-errors --retry-delay 2 -sS -X PUT "${R2_API}/$1" \
    -H @"${HDR}" -H "Content-Type: $3" --data-binary "@$2" -o "${resp}"
  python3 - "$1" "${resp}" <<'PY'
import json, sys
key, path = sys.argv[1], sys.argv[2]
raw = open(path).read()
try:
    r = json.loads(raw)
except json.JSONDecodeError:
    sys.exit(f"  {key}: FAIL, reply is not JSON: {raw[:200]}")
if r.get("success") is True:
    print(f"  {key}: OK")
else:
    sys.exit(f"  {key}: FAIL {r.get('errors')}")
PY
}

echo "==> Uploading to R2 (${R2_BUCKET})"
upload "${KEY_APK}" "${APK}" "application/vnd.android.package-archive"
upload "bridget/api.html" "${DOCS}" "text/html; charset=utf-8"
# Verify the immutable APK before making the updater advertise it.
curl -fsSL -A "${UA}" --max-time 600 -o "${WORK}/preflight.apk" "${URL}" || die "couldn't verify the uploaded APK"
[[ "$(shasum -a 256 "${WORK}/preflight.apk" | awk '{print $1}')" == "${SHA}" ]] || die "uploaded APK hash mismatch"
upload "${KEY_JSON}" "${MANIFEST}" "application/json"
rm -f "${HDR}"; HDR=""

# ---- 6. Verify the public copies ----
echo "==> Verifying the public copies"
curl -fsSL -A "${UA}" -H "Cache-Control: no-cache" --max-time 60 -o "${WORK}/pub.json" "${PUBLIC_BASE}/latest.json?v=${VCODE}" \
  || die "couldn't download the public latest.json"
python3 - "${WORK}/pub.json" "${VCODE}" "${SHA}" "${URL}" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
if m.get("version_code") != int(sys.argv[2]) or m.get("sha256") != sys.argv[3] or m.get("url") != sys.argv[4]:
    sys.exit(f"public latest.json doesn't match what was uploaded: {m}")
print("  latest.json: OK")
PY
curl -fsSL -A "${UA}" --max-time 600 -o "${WORK}/pub.apk" "${URL}" || die "couldn't download the public APK"
PUB_SHA="$(shasum -a 256 "${WORK}/pub.apk" | awk '{print $1}')"
[[ "${PUB_SHA}" == "${SHA}" ]] || die "public APK SHA-256 ${PUB_SHA} != ${SHA}"
echo "  ${KEY_APK}: OK (SHA-256 matches)"

# ---- 7. GitHub release ----
if [[ ${GITHUB} -eq 1 ]]; then
  command -v gh >/dev/null || die "gh not found (or pass --no-github)"
  ASSET="${WORK}/bridget-${VNAME}.apk"
  cp "${APK}" "${ASSET}"
  echo "==> Creating GitHub release bridget-v${VNAME}"
  # Tag the commit the APK was built from (it must be pushed), not the default branch.
  gh release create "bridget-v${VNAME}" "${ASSET}" --repo "${GH_REPO}" --target "${COMMIT}" --title "Bridgit ${VNAME}" \
    --notes "${NOTES}

SHA-256: ${SHA}
Download: ${URL}"
fi

echo "==> Published Bridgit ${VNAME} (${VCODE}): ${URL}"
