#!/usr/bin/env bash
# Slice 9c, the release policy for image scans: a CRITICAL vulnerability that already has a fixed version
# fails the build; everything else (CRITICAL without a fix, HIGH, MEDIUM, LOW) is reported only. Reads the
# Trivy JSON reports the scan step wrote, so the database is not downloaded twice.
set -euo pipefail

blocking=0
for report in "$@"; do
  found=$(jq -r '[.Results[].Vulnerabilities[]? | select(.Severity == "CRITICAL" and (.FixedVersion // "") != "")]
      | unique_by(.VulnerabilityID + .PkgName) | .[]
      | "\(.VulnerabilityID) \(.PkgName) \(.InstalledVersion) (fixed in \(.FixedVersion))"' "$report")
  if [ -n "$found" ]; then
    echo "$(jq -r .ArtifactName "$report"): CRITICAL with a fix available:"
    printf '%s\n' "$found" | sed 's/^/  /'
    blocking=$((blocking + $(printf '%s\n' "$found" | wc -l)))
  fi
done
if [ "$blocking" -gt 0 ]; then
  echo "trivy-gate: $blocking critical vulnerabilities have a fix; update the dependency or the base image." >&2
  exit 1
fi
echo "trivy-gate: no CRITICAL vulnerability with a fix available (other findings are reported, not blocking)."
