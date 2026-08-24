#!/bin/bash
# Copies the local nightly snapshots somewhere that is not this machine.
#
# backup-db.sh has carried a note since it was written saying it protects
# against corruption, a bad migration and an accidental delete, but not against
# losing the disk -- and that off-host replication was the natural next step,
# left out because it needs credentials the script didn't have.
#
# On 2026-08-24 the VM was lost outright, instance and boot volume both. There
# were fourteen nightly backups. All fourteen were on that disk. Every user's
# meals, weights, water and workouts went with it.
#
# So: this is the other half. It uses rclone, which speaks Oracle Object
# Storage, S3, B2 and everything else, so the destination is a config choice
# rather than a rewrite.
#
# SETUP (once, on the VM):
#   sudo apt-get install -y rclone
#   rclone config          # create a remote; name it whatever REMOTE says below
#   rclone lsd <remote>:   # prove it authenticates BEFORE trusting it
#
# CRON (after backup-db.sh's 03:00 run has finished):
#   30 3 * * * $HOME/duabiskuttelur/ops/backup-sync.sh >> $HOME/backups/sync.log 2>&1
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
REMOTE="${REMOTE:-duabiskuttelur-backups}"
REMOTE_PATH="${REMOTE_PATH:-$REMOTE:duabiskuttelur/db}"
# Longer than backup-db.sh's local 14 days: off-host copies are the ones that
# survive the failure this exists for, and they cost a few megabytes a month.
RETENTION_DAYS="${RETENTION_DAYS:-60}"

log() { printf '%s  %s\n' "$(date -Is)" "$*"; }

command -v rclone >/dev/null || { log "FAILED: rclone is not installed"; exit 1; }

if ! rclone listremotes | grep -qx "$REMOTE:"; then
    log "FAILED: no rclone remote named '$REMOTE'. Run: rclone config"
    exit 1
fi

[ -d "$BACKUP_DIR" ] || { log "FAILED: $BACKUP_DIR does not exist"; exit 1; }

newest=$(find "$BACKUP_DIR" -name 'duabiskuttelur-db-*.tar.gz' -printf '%T@ %p\n' 2>/dev/null \
         | sort -rn | head -1 | cut -d' ' -f2-)
if [ -z "$newest" ]; then
    log "FAILED: no snapshots in $BACKUP_DIR -- is backup-db.sh installed in cron?"
    exit 1
fi

# A snapshot older than two days means backup-db.sh has stopped running, and
# syncing it forever would look exactly like a healthy backup. Loudly, then.
age_days=$(( ( $(date +%s) - $(stat -c %Y "$newest") ) / 86400 ))
if [ "$age_days" -gt 2 ]; then
    log "WARNING: newest snapshot is ${age_days} days old ($(basename "$newest"))."
    log "WARNING: backup-db.sh looks like it has stopped running. Check its cron entry."
fi

log "Syncing $BACKUP_DIR -> $REMOTE_PATH"
rclone copy "$BACKUP_DIR" "$REMOTE_PATH" \
    --include 'duabiskuttelur-db-*.tar.gz' \
    --transfers 2 \
    --retries 3 \
    --stats-one-line

# Verify by reading it back, not by trusting the exit code. An upload that
# reports success and leaves nothing behind is the exact failure this file is
# an apology for.
remote_name=$(basename "$newest")
if rclone lsf "$REMOTE_PATH/$remote_name" >/dev/null 2>&1; then
    size=$(rclone size "$REMOTE_PATH" --json 2>/dev/null | grep -o '"bytes":[0-9]*' | cut -d: -f2)
    log "OK: $remote_name is present off-host (${size:-?} bytes across all copies)"
else
    log "FAILED: $remote_name is NOT readable at $REMOTE_PATH after the copy"
    exit 1
fi

log "Pruning off-host copies older than ${RETENTION_DAYS} days"
rclone delete "$REMOTE_PATH" \
    --include 'duabiskuttelur-db-*.tar.gz' \
    --min-age "${RETENTION_DAYS}d"

log "Done"
