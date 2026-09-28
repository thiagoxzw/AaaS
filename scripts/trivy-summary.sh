#!/usr/bin/env bash
# Slice 9c: turns Trivy JSON reports into a markdown summary for the CI job page: what the images contain, by
# severity. It never fails the build itself; scripts/trivy-gate.sh applies the release policy (a CRITICAL with a
# fix available blocks), docs/fatias/09c-release.md.
set -euo pipefail

echo "## Image scan (Trivy)"
for report in "$@"; do
  image=$(jq -r '.ArtifactName' "$report")
  os=$(jq -r '.Metadata.OS | "\(.Family) \(.Name)"' "$report")
  echo
  echo "### \`$image\` ($os)"
  echo
  echo "| Target | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN |"
  echo "|---|---|---|---|---|---|"
  jq -r '.Results[] | [.Target, ([.Vulnerabilities[]?.Severity] as $s |
      ("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN") | . as $level | [$s[] | select(. == $level)] | length)]
      | "| \(.[0]) | \(.[1:] | map(tostring) | join(" | ")) |"' "$report"
  blocking=$(jq -r '[.Results[].Vulnerabilities[]? | select(.Severity == "CRITICAL" or .Severity == "HIGH")] | length' "$report")
  if [ "$blocking" -gt 0 ]; then
    echo
    echo "| Severity | Vulnerability | Package | Installed | Fixed in |"
    echo "|---|---|---|---|---|"
    jq -r '[.Results[].Vulnerabilities[]? | select(.Severity == "CRITICAL" or .Severity == "HIGH")]
        | unique_by(.VulnerabilityID + .PkgName) | .[]
        | "| \(.Severity) | \(.VulnerabilityID) | \(.PkgName) | \(.InstalledVersion) | \(.FixedVersion // "no fix yet") |"' "$report"
  fi
done
