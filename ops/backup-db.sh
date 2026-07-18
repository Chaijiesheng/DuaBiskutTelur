#!/bin/bash
# Nightly backup of the H2 data volume. Runs on the VM via cron (see
# ops/README.md for the crontab line) — not part of the app image itself.
#
# Briefly stops the backend (a few seconds) rather than tar'ing the live
# .mv.db file, because MVStore isn't guaranteed consistent under a naive
# hot-copy: a backup that might be silently corrupt is worse than a few
# seconds of downtime at 3am. Retention keeps this from growing unbounded.
#
# Known gap: this writes backups to the same VM disk as the database, so it
# protects against corruption / accidental deletion / a bad migration, but
# NOT against losing the VM's disk entirely. Off-host replication (e.g. an
# rclone/aws-cli sync to object storage) is the natural next step — it needs
# storage credentials this script doesn't have, so it's not wired in here.
set -euo pipefail

# Override via environment if your layout differs.
COMPOSE_DIR="${COMPOSE_DIR:-$HOME/duabiskuttelur}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
RETENTION_DAYS=14
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
VOLUME="duabiskuttelur_backend-data"

mkdir -p "$BACKUP_DIR"
cd "$COMPOSE_DIR"

docker compose stop backend
docker run --rm \
  -v "$VOLUME":/data:ro \
  -v "$BACKUP_DIR":/backup \
  alpine tar -czf "/backup/duabiskuttelur-db-$TIMESTAMP.tar.gz" -C /data .
docker compose start backend

find "$BACKUP_DIR" -name 'duabiskuttelur-db-*.tar.gz' -mtime "+$RETENTION_DAYS" -delete
