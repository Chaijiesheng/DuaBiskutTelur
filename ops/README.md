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

## bootstrap.sh

Bare Ubuntu VM to a running deployment. Written after the production VM was
lost outright on 2026-08-24 -- instance and boot volume both -- and the rebuild
turned out to exist only as steps somebody remembered doing once.

```
curl -fsSL https://raw.githubusercontent.com/Chaijiesheng/DuaBiskutTelur/main/ops/bootstrap.sh -o bootstrap.sh
bash bootstrap.sh
```

Idempotent; safe to re-run. It installs Docker, opens the host firewall (Oracle
Ubuntu images drop everything but SSH, which is the usual reason a fresh VM
serves nothing despite a correct security list), clones, stops if `.env` is not
filled in, binds the frontend to port 80 via an override file, builds, and
verifies. It does not write secrets, touch DNS, or open the OCI security list.

## backup-sync.sh

Copies the local snapshots off the machine, via rclone. **`backup-db.sh` alone
is not a backup** -- it writes to the same disk as the database, which is why
fourteen nightly snapshots were lost along with the VM that held them.

```
sudo apt-get install -y rclone
rclone config          # name the remote duabiskuttelur-backups
rclone lsd duabiskuttelur-backups:     # prove it authenticates first

crontab -e
30 3 * * * $HOME/duabiskuttelur/ops/backup-sync.sh >> $HOME/backups/sync.log 2>&1
```

Runs half an hour after `backup-db.sh`. It reads the copy back from the remote
rather than trusting rclone's exit code, warns if the newest local snapshot is
more than two days old (which means `backup-db.sh` has quietly stopped), and
keeps 60 days off-host against the local 14.
