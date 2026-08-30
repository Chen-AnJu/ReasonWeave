#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
secret_directory="$repository_root/.local/secrets"
password_path="$secret_directory/postgres_password"

umask 077
mkdir -p "$secret_directory"
if [ -e "$password_path" ]; then
  printf '%s\n' 'Local configuration already exists; no secret was overwritten.'
  exit 0
fi

temporary_path="$secret_directory/.postgres_password.$$"
trap 'rm -f "$temporary_path"' EXIT HUP INT TERM
od -An -N32 -tx1 /dev/urandom | tr -d ' \n' > "$temporary_path"
printf '\n' >> "$temporary_path"
chmod 0644 "$temporary_path"

if ! ln "$temporary_path" "$password_path" 2>/dev/null; then
  printf '%s\n' 'Local configuration already exists; no secret was overwritten.'
  exit 0
fi
rm -f "$temporary_path"
trap - EXIT HUP INT TERM
printf '%s\n' 'Created .local/secrets/postgres_password'
