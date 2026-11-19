#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
paper_api_version=${PAPER_API_VERSION:-26.3}
# Paper API artifact names differ between the 26.x stable line and the 1.21.x
# snapshot line. PAPER_API_ARTIFACT can override the mapping for a future build.
if [[ -n "${PAPER_API_ARTIFACT:-}" ]]; then
  paper_api_artifact=${PAPER_API_ARTIFACT}
else
  case "${paper_api_version}" in
    26.3) paper_api_artifact=26.3.build.254-stable ;;
    26.1.2) paper_api_artifact=26.1.2.build.74-stable ;;
    1.21.11) paper_api_artifact=1.21.11-R0.1-SNAPSHOT ;;
    1.21.1) paper_api_artifact=1.21.1-R0.1-SNAPSHOT ;;
    *)
      printf 'No Paper API artifact mapping for %s; set PAPER_API_ARTIFACT explicitly.\n' "${paper_api_version}" >&2
      exit 2
      ;;
  esac
fi
if [[ -n "${PAPER_PLUGIN_API_VERSION:-}" ]]; then
  plugin_api_version=${PAPER_PLUGIN_API_VERSION}
elif [[ "${paper_api_version}" == 1.21* ]]; then
  plugin_api_version=1.21
else
  plugin_api_version=${paper_api_version}
fi

output=${1:-"${root_dir}/dist/CodFrame-1.3.0-${paper_api_version}.jar"}
cd "${root_dir}"
mvn -q clean package \
  -Dpaper.version="${paper_api_version}" \
  -Dpaper-api.version="${paper_api_artifact}" \
  -Dpaper.api-version="${plugin_api_version}"

mkdir -p "$(dirname "${output}")"
cp "${root_dir}/target/CodFrame-1.3.0-${paper_api_version}.jar" "${output}"
printf 'Built %s from CodFrame %s for Paper %s (plugin api-version %s).\n' \
  "${output}" "$(git rev-parse --short HEAD)" "${paper_api_version}" "${plugin_api_version}"
