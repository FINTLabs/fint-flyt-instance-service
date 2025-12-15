#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE_PATH="$ROOT/kustomize/templates/overlay.yaml.tpl"

ROLE_CATALOG_USER_URL="USER"
ROLE_CATALOG_DEVELOPER_URL="DEVELOPER"

build_role_mapping() {
  local namespace="$1"
  local org_id_dot="$2"
  local mapping=""

  case "$namespace" in
    afk-no|bfk-no|ofk-no)
      mapping=$(printf '            {\n              "%s":["%s"],\n              "viken.no":["%s"],\n              "frid-iks.no":["%s"],\n              "vigo.no":["%s", "%s"],\n              "novari.no":["%s", "%s"]\n            }\n' \
        "$org_id_dot" \
        "$ROLE_CATALOG_USER_URL" \
        "$ROLE_CATALOG_USER_URL" \
        "$ROLE_CATALOG_USER_URL" \
        "$ROLE_CATALOG_DEVELOPER_URL" \
        "$ROLE_CATALOG_USER_URL" \
        "$ROLE_CATALOG_DEVELOPER_URL" \
        "$ROLE_CATALOG_USER_URL")
      ;;
    *)
      mapping=$(printf '            {\n              "%s":["%s"],\n              "vigo.no":["%s", "%s"],\n              "novari.no":["%s", "%s"]\n            }\n' \
        "$org_id_dot" \
        "$ROLE_CATALOG_USER_URL" \
        "$ROLE_CATALOG_DEVELOPER_URL" \
        "$ROLE_CATALOG_USER_URL" \
        "$ROLE_CATALOG_DEVELOPER_URL" \
        "$ROLE_CATALOG_USER_URL")
      ;;
  esac

  printf '%s\n' "$mapping"
}

while IFS= read -r file; do
  rel="${file#"$ROOT/kustomize/overlays/"}"
  dir="$(dirname "$rel")"

  namespace="${dir%%/*}"
  env_path="${dir#*/}"
  if [[ "$env_path" == "$namespace" || "$env_path" == "$dir" ]]; then
    env_path=""
  fi

  environment="${env_path:-api}"
  if [[ -z "$environment" ]]; then
    environment="api"
  fi

  url_prefix=""
  if [[ "$environment" != "api" ]]; then
    url_prefix="${environment}/"
  fi

  export NAMESPACE="$namespace"
  export ORG_ID_DOT="${namespace//-/.}"
  export ORG_ID_UNDERSCORE="${namespace//-/_}"
  export APP_INSTANCE_LABEL="fint-flyt-instance-service_${ORG_ID_UNDERSCORE}"
  export KAFKA_TOPIC="${NAMESPACE}.flyt.*"

  base_path="/${url_prefix}${NAMESPACE}"
  export BASE_PATH="$base_path"
  export INGRESS_BASE_PATH="${base_path}/api/intern/handlinger/instanser"
  export READINESS_PATH="${base_path}/actuator/health"
  export METRICS_PATH="${base_path}/actuator/prometheus"
  export FINT_KAFKA_TOPIC_ORGID="${namespace}"

  export ROLE_MAPPING="$(build_role_mapping "$namespace" "$ORG_ID_DOT")"
  if [[ -z "$ROLE_MAPPING" ]]; then
    echo "Unable to determine role mapping for namespace '${namespace}'" >&2
    exit 1
  fi

  target_dir="$ROOT/kustomize/overlays/$dir"
  tmp="$(mktemp)"
  envsubst < "$TEMPLATE_PATH" > "$tmp"
  mv "$tmp" "$target_dir/kustomization.yaml"
done < <(find "$ROOT/kustomize/overlays" -name kustomization.yaml -print | sort)
