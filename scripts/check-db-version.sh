#!/usr/bin/env bash
set -euo pipefail

repo_root="${1:-$(pwd)}"

migration_dir="$repo_root/docs-core/src/main/resources/db/update"
config_files=(
  "docs-core/src/main/resources/config.properties"
  "docs-web/src/dev/resources/config.properties"
  "docs-web/src/prod/resources/config.properties"
)

if [[ ! -d "$migration_dir" ]]; then
  echo "Missing migration directory: $migration_dir" >&2
  exit 1
fi

max_version=-1
found_migration=0

while IFS= read -r -d '' migration; do
  filename="$(basename "$migration")"
  if [[ "$filename" =~ ^dbupdate-([0-9]{3})-[0-9]+\.sql$ ]]; then
    version=$((10#${BASH_REMATCH[1]}))
    if (( version > max_version )); then
      max_version="$version"
    fi
    found_migration=1
  fi
done < <(find "$migration_dir" -maxdepth 1 -type f -name 'dbupdate-*-*.sql' -print0)

if (( found_migration == 0 )); then
  echo "No dbupdate-NNN-M.sql migrations found in $migration_dir" >&2
  exit 1
fi

failed=0

for rel_path in "${config_files[@]}"; do
  config_file="$repo_root/$rel_path"

  if [[ ! -f "$config_file" ]]; then
    echo "$rel_path is missing; expected db.version >= $max_version" >&2
    failed=1
    continue
  fi

  db_version_line="$(grep -E '^[[:space:]]*db\.version[[:space:]]*=' "$config_file" | tail -n 1 || true)"
  db_version="${db_version_line#*=}"
  db_version="${db_version//[[:space:]]/}"

  if [[ -z "$db_version_line" || ! "$db_version" =~ ^[0-9]+$ ]]; then
    echo "$rel_path has no numeric db.version; expected db.version >= $max_version" >&2
    failed=1
    continue
  fi

  db_version_number=$((10#$db_version))
  if (( db_version_number < max_version )); then
    echo "$rel_path has db.version=$db_version, but latest migration is dbupdate-$(printf '%03d' "$max_version"); bump this file to at least $max_version" >&2
    failed=1
  fi
done

if (( failed != 0 )); then
  exit 1
fi

echo "All db.version values are >= latest migration dbupdate-$(printf '%03d' "$max_version")."
