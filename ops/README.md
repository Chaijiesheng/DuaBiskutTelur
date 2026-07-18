# ops/

Scripts that run on the host VM itself, outside the Docker Compose app — not
part of the deployed image.

## backup-db.sh

Nightly H2 volume backup with a 14-day retention. Installed via cron on the
VM (not automatically re-installed by deploys — a one-time setup step):

```
crontab -e
# add (adjust paths to where you cloned the project):
0 3 * * * $HOME/duabiskuttelur/ops/backup-db.sh >> $HOME/backups/backup.log 2>&1
```

Backups land in `$HOME/backups/` on the VM by default; override the
`COMPOSE_DIR` / `BACKUP_DIR` environment variables if your layout differs.
See the script's header comment for what this does and doesn't protect
against.
