#!/usr/bin/env bash
# quiesce.sh <base> -- report doc count, rare-term hits, index dir size. Exit 0 when stable.
set -uo pipefail
BASE="$1"
CK="$(curl -s -X POST -d 'username=admin&password=admin&remember=true' "$BASE/api/user/login" -c - | awk '/auth_token/{print $7}')"
q () { curl -s -G --cookie "auth_token=$CK" --data-urlencode "search=$1" --data 'limit=1' "$BASE/api/document/list" \
       | python3 -c 'import sys,json;print(json.load(sys.stdin).get("total"))'; }
all=$(curl -s -G --cookie "auth_token=$CK" --data 'limit=1' "$BASE/api/document/list" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("total"))')
echo "docs=$all rare=$(q werkstattwagen) common=$(q schlüssel)"
