#!/bin/sh
set -eu
umask 077

root="${GEN2SPRING_RUNNER_ROOT:-}"
input="${root}/job/input"
output="${root}/job/output"
work="${root}/job/work"
temporary="${root}/tmp"
project="${work}/generated-project"
cli="${root}/opt/gen2spring/openapi-mcp/bin/openapi-mcp"
seed="${root}/opt/gen2spring/gradle-seed"
result="${output}/result.json"
outcome="FAILED"
exit_code=125

finish() {
  status=$?
  trap - EXIT
  rm -f "${output}/.staging-archive" \
    "${output}/.staging-manifest" \
    "${output}/.staging-validation" \
    "${output}/.staging-result"
  if [ "${outcome}" != "SUCCESS" ]; then
    rm -f "${output}/archive.zip" "${output}/manifest.json" "${output}/validation-report.json"
    if [ "${status}" -gt 0 ] && [ "${status}" -le 255 ]; then
      exit_code=${status}
    fi
  fi
  printf '{"outcome":"%s","exitCode":%s}\n' "${outcome}" "${exit_code}" > "${output}/.staging-result"
  mv "${output}/.staging-result" "${result}"
  exit "${status}"
}
trap finish EXIT

[ -d "${input}" ] && [ ! -L "${input}" ]
[ -d "${output}" ] && [ ! -L "${output}" ]
[ -d "${work}" ] && [ ! -L "${work}" ]
[ -x "${cli}" ] && [ ! -L "${cli}" ]
[ ! -e "${input}/specification.yaml" ] || [ ! -e "${input}/specification.json" ]
if [ -f "${input}/specification.yaml" ] && [ ! -L "${input}/specification.yaml" ]; then
  specification="${input}/specification.yaml"
elif [ -f "${input}/specification.json" ] && [ ! -L "${input}/specification.json" ]; then
  specification="${input}/specification.json"
else
  exit 125
fi
[ -f "${input}/generation-config.json" ] && [ ! -L "${input}/generation-config.json" ]
[ -d "${seed}/caches/modules-2" ] && [ -d "${seed}/wrapper/dists" ]
[ -z "$(find "${output}" -mindepth 1 -maxdepth 1 -print -quit)" ]

mkdir -p "${work}/gradle-home/wrapper"
cp -R "${seed}/wrapper/dists" "${work}/gradle-home/wrapper/dists"
chmod -R u+rwX "${work}/gradle-home"
export GRADLE_USER_HOME="${work}/gradle-home"
export GRADLE_RO_DEP_CACHE="${seed}/caches"

set +e
"${cli}" generate \
  --spec "${specification}" \
  --config "${input}/generation-config.json" \
  --output "${project}" > "${temporary}/generation.log" 2>&1
exit_code=$?
set -e
if [ "${exit_code}" -ne 0 ]; then
  exit "${exit_code}"
fi

[ -f "${project}.zip" ] && [ ! -L "${project}.zip" ]
[ -f "${project}/GENERATION_MANIFEST.json" ] && [ ! -L "${project}/GENERATION_MANIFEST.json" ]
[ -f "${project}/VALIDATION_REPORT.json" ] && [ ! -L "${project}/VALIDATION_REPORT.json" ]

cp "${project}.zip" "${output}/.staging-archive"
cp "${project}/GENERATION_MANIFEST.json" "${output}/.staging-manifest"
cp "${project}/VALIDATION_REPORT.json" "${output}/.staging-validation"
chmod 0600 "${output}/.staging-archive" "${output}/.staging-manifest" "${output}/.staging-validation"
mv "${output}/.staging-archive" "${output}/archive.zip"
mv "${output}/.staging-manifest" "${output}/manifest.json"
mv "${output}/.staging-validation" "${output}/validation-report.json"
outcome="SUCCESS"
exit_code=0
