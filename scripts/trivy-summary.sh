#!/usr/bin/env bash
# Slice 9c: turns Trivy JSON reports into a markdown summary for the CI job page. Report-only: it lists what
# the images contain by severity and never fails the build (which severities block a release is a separate
# decision, docs/fatias/09c-release.md).
set -euo pipefail

echo "## Image scan (Trivy, report-only)"
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
