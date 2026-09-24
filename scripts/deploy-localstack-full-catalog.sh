#!/usr/bin/env bash
# Deploys every application in the catalog to LocalStack, one at a time, tearing down each
# stack before starting the next. Deploying without teardown between apps exhausts LocalStack's
# own memory once ~15 stacks are live simultaneously (each Fargate/RDS-backed app spins up real
# nested Docker containers) and OOM-kills the LocalStack container itself, taking every
# in-flight deploy down with it. Real pass/fail is read from CloudFormation's own StackStatus,
# not from scraping deploy-tool console output.
#
# Requires LOCALSTACK_AUTH_TOKEN and cloudforge-cli on PATH.
# Usage: ./deploy-localstack-full-catalog.sh [output-tsv-path]

set -uo pipefail
cd "$(dirname "$0")/.."
export AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-http://localhost:4566}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"
unset CFC_DEPLOYING

RESULTS="${1:-scripts/validation-results/localstack-full-catalog-results.tsv}"
mkdir -p "$(dirname "$RESULTS")"
echo -e "app\tcontext\tresult\tnote" > "$RESULTS"

deploy_and_teardown() {
  local app="$1"
  local ctx="$2"
  local stack_guess="${ctx%.json}"
  stack_guess="${stack_guess%-LocalStack}"
  local stack="${stack_guess}-localstack"

  echo "========== Deploying $app to LocalStack =========="
  cloudforge-cli deploy --context "docs/deployment-contexts/$ctx" --target localstack > /tmp/deploy-$$.log 2>&1
  tail -15 /tmp/deploy-$$.log

  local status
  status=$(curl -s "http://localhost:4566/?Action=DescribeStacks&StackName=${stack}&Version=2010-05-15" \
    | grep -o "<StackStatus>[^<]*</StackStatus>" | sed -E 's#</?StackStatus>##g')

  if [ "$status" = "CREATE_COMPLETE" ] || [ "$status" = "UPDATE_COMPLETE" ]; then
    echo -e "${app}\t${ctx}\tPASS\t${status}" >> "$RESULTS"
  elif [ -n "$status" ]; then
    echo -e "${app}\t${ctx}\tFAIL\t${status}" >> "$RESULTS"
  else
    echo -e "${app}\t${ctx}\tFAIL\tno stack found (deploy never reached CloudFormation)" >> "$RESULTS"
  fi
  rm -f /tmp/deploy-$$.log

  echo "---- tearing down $stack ----"
  curl -s "http://localhost:4566/?Action=DeleteStack&StackName=${stack}&Version=2010-05-15" > /dev/null
  for i in $(seq 1 40); do
    local remaining
    remaining=$(curl -s "http://localhost:4566/?Action=DescribeStacks&StackName=${stack}&Version=2010-05-15" \
      | grep -o "<StackStatus>[^<]*</StackStatus>" | grep -v DELETE_COMPLETE || true)
    [ -z "$remaining" ] && break
    sleep 3
  done
  docker ps -aq --filter "name=^ls-" | xargs -r docker rm -f > /dev/null 2>&1 || true
}

# ---- non-RDS (11) + cloudforge-manager ----
deploy_and_teardown cloudforge-manager CloudForgeManager-Dev.json
deploy_and_teardown jenkins Jenkins-Stack.json
deploy_and_teardown metabase Metabase-Stack.json
deploy_and_teardown mattermost-enterprise Mattermost-Stack-LocalStack.json
deploy_and_teardown prometheus Prometheus-Stack.json
deploy_and_teardown vault Vault-Stack.json
deploy_and_teardown redis Redis-Stack.json
deploy_and_teardown nexus Nexus-Stack.json
deploy_and_teardown sonarqube SonarQube-Stack.json
deploy_and_teardown postgresql PostgreSQL-Stack.json
deploy_and_teardown drone Drone-Stack.json
deploy_and_teardown grafana Grafana-Stack.json
deploy_and_teardown gitea Gitea-Stack.json

# ---- CMS / RDS-tier catalog ----
deploy_and_teardown wordpress WordPress-Stack-LocalStack.json
deploy_and_teardown joomla Joomla-Stack-LocalStack.json
deploy_and_teardown drupal Drupal-Stack-LocalStack.json
deploy_and_teardown woocommerce WooCommerce-Stack-LocalStack.json
deploy_and_teardown magento Magento-Stack-LocalStack.json
deploy_and_teardown prestashop PrestaShop-Stack-LocalStack.json
deploy_and_teardown opencart OpenCart-Stack-LocalStack.json
deploy_and_teardown moodle Moodle-Stack-LocalStack.json
deploy_and_teardown mediawiki MediaWiki-Stack-LocalStack.json
deploy_and_teardown typo3 TYPO3-Stack-LocalStack.json
deploy_and_teardown sylius Sylius-Stack-LocalStack.json
deploy_and_teardown bagisto Bagisto-Stack-LocalStack.json
deploy_and_teardown flarum Flarum-Stack-LocalStack.json
deploy_and_teardown mybb MyBB-Stack-LocalStack.json
deploy_and_teardown phpbb phpBB-Stack-LocalStack.json
deploy_and_teardown suitecrm SuiteCRM-Stack-LocalStack.json
deploy_and_teardown concrete-cms ConcreteCMS-Stack-LocalStack.json
deploy_and_teardown october-cms OctoberCMS-Stack-LocalStack.json
deploy_and_teardown dolphin-una DolphinUNA-Stack-LocalStack.json
deploy_and_teardown superset Superset-Stack-LocalStack.json
deploy_and_teardown gitlab GitLab-Stack-LocalStack.json
deploy_and_teardown harbor Harbor-Stack-LocalStack.json
deploy_and_teardown mattermost-team MattermostTeam-Stack-LocalStack.json

echo "===== FULL CATALOG SWEEP DONE ====="
column -t -s$'\t' "$RESULTS"
FAILED=$(awk -F'\t' 'NR>1 && $3=="FAIL"' "$RESULTS" | wc -l | tr -d ' ')
echo "Failures: $FAILED"
[ "$FAILED" -eq 0 ]
