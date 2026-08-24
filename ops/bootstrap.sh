#!/bin/bash
# Bare Ubuntu VM -> running deployment.
#
# Written after the production VM was lost outright on 2026-08-24 (instance and
# boot volume both gone) and the rebuild turned out to be undocumented: the
# steps existed only as things somebody remembered doing once. This script is
# that memory, made executable.
#
# It is idempotent. Re-running it on a working host is a no-op that re-verifies.
#
# What it deliberately does NOT do:
#   - write .env for you. Six secrets, none of them belong in a script, and a
#     generated placeholder that boots is worse than a stop that tells you why.
#   - touch DNS. Cloudflare's A record is the one step that must be done by a
#     human who can see which IP the instance actually got.
#   - open the OCI security list / network security group. That is console-side
#     and cannot be scripted from inside the VM. Host firewall IS handled below.
set -euo pipefail

REPO="${REPO:-https://github.com/Chaijiesheng/DuaBiskutTelur.git}"
DIR="${DIR:-$HOME/duabiskuttelur}"
# Bind the frontend publicly by default. The compose file ships 127.0.0.1:8081
# for shared hosts fronted by the host's own nginx; a rebuilt single-purpose VM
# has no host nginx, and forgetting this is a site that answers only to itself.
PUBLISH="${PUBLISH:-80}"

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mSTOP: %s\033[0m\n' "$*" >&2; exit 1; }

# --------------------------------------------------------------- packages
say "Installing Docker and friends"
if ! command -v docker >/dev/null; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq ca-certificates curl git
    sudo install -m 0755 -d /etc/apt/keyrings
    sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        -o /etc/apt/keyrings/docker.asc
    sudo chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
        | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
    sudo apt-get update -qq
    sudo apt-get install -y -qq docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin
    sudo usermod -aG docker "$USER"
    NEEDS_RELOGIN=1
else
    echo "Docker already present: $(docker --version)"
fi

# --------------------------------------------------------------- firewall
# Oracle's Ubuntu images ship an iptables ruleset that drops everything except
# SSH, and it survives reboots via netfilter-persistent. Every fresh Oracle VM
# hits this: the security list is open, docker is running, and the site still
# times out. Opening it here rather than leaving it as folklore.
say "Opening port $PUBLISH on the host firewall"
if command -v iptables >/dev/null; then
    if ! sudo iptables -C INPUT -p tcp --dport "$PUBLISH" -j ACCEPT 2>/dev/null; then
        # Insert above the catch-all REJECT that Oracle's ruleset ends with.
        sudo iptables -I INPUT 6 -p tcp --dport "$PUBLISH" -m state --state NEW -j ACCEPT
        if command -v netfilter-persistent >/dev/null; then
            sudo netfilter-persistent save
        else
            echo "WARNING: netfilter-persistent absent; this rule will not survive a reboot."
        fi
    else
        echo "Port $PUBLISH already accepted."
    fi
fi
echo "Reminder: the OCI security list (or NSG) for this subnet must also allow $PUBLISH/tcp."

# --------------------------------------------------------------- checkout
say "Fetching the application"
if [ -d "$DIR/.git" ]; then
    git -C "$DIR" pull --ff-only
else
    git clone "$REPO" "$DIR"
fi
cd "$DIR"

# --------------------------------------------------------------- secrets
if [ ! -f .env ]; then
    cp .env.example .env
    chmod 600 .env
    die "Wrote a blank .env from .env.example. Fill in the six values, then re-run.
     GEMINI_API_KEY (+ _2, _3)   https://aistudio.google.com
     USDA_API_KEY                https://fdc.nal.usda.gov/api-key-signup
     GOOGLE_CLIENT_ID / SECRET   https://console.cloud.google.com -> Credentials
     The OAuth client needs https://<your-domain>/login/oauth2/code/google
     listed as an authorised redirect URI, or sign-in fails at Google."
fi
chmod 600 .env
for required in GEMINI_API_KEY GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET; do
    # Requires a non-whitespace character, not merely something after the "=".
    # "KEY=   " passes a .+ test and then boots happily into mock mode, serving
    # a fabricated nasi lemak analysis to real users -- a failure that looks
    # exactly like success.
    if ! grep -qE "^$required=[[:space:]]*[^[:space:]]" .env; then
        die "$required is empty in .env. Fill it in and re-run."
    fi
done
echo ".env present, permissions 600, required keys non-empty."

# --------------------------------------------------------------- publish
# A single-purpose host has no front proxy, so the container must own port 80.
if [ "$PUBLISH" = "80" ] && grep -q '127.0.0.1:8081:80' docker-compose.yml; then
    say "Binding the frontend to 0.0.0.0:80"
    # An override file rather than an edit: docker-compose.yml stays as the
    # shared-host default it documents itself as, and `git pull` keeps working.
    cat > docker-compose.override.yml <<'OVERRIDE'
# Written by ops/bootstrap.sh for a host where this is the only site.
# docker-compose.yml binds the frontend to 127.0.0.1:8081 for shared hosts that
# put their own nginx in front; here there is nothing in front, so the container
# takes port 80 itself. Delete this file if you add a host-level proxy.
services:
  frontend:
    ports: !override
      - "80:80"
OVERRIDE
fi

# --------------------------------------------------------------- build
say "Building (slow on ARM from cold -- the backend's apt layer alone can take 20+ minutes)"
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then DOCKER="sudo docker"; fi
$DOCKER compose build
$DOCKER compose up -d

# --------------------------------------------------------------- verify
say "Waiting for the site to answer"
base="http://127.0.0.1:${PUBLISH}"
for _ in $(seq 1 60); do
    shell_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$base/" 2>/dev/null || echo 000)
    [ "$shell_code" = "200" ] && break
    sleep 5
done
[ "$shell_code" = "200" ] || die "The site did not come up. Try: $DOCKER compose logs --tail=80"

# The backend, separately -- nginx serving the shell over a dead backend is the
# failure that looks fine until somebody taps something.
health_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$base/actuator/health" 2>/dev/null || echo 000)
case "$health_code" in
    200) echo "Backend healthy: $(curl -s --max-time 10 "$base/actuator/health")" ;;
    404) echo "WARNING: /actuator/health is not proxied by this checkout, so the backend
         was not verified and the uptime workflow will report the site as down.
         Pull a revision that has the health location in frontend/nginx.conf." ;;
    *)   die "nginx is serving the app but the backend answered $health_code.
     The site will look fine and fail on the first tap. Try: $DOCKER compose logs backend --tail=80" ;;
esac
say "Up. Remaining steps that cannot be done from inside this VM:"
cat <<'NEXT'
  1. Point the Cloudflare A record at this instance's public IP.
     Use a RESERVED IP -- an ephemeral one is released when the instance stops,
     and a stale A record is exactly the 522 that started all this.
  2. Confirm the OCI security list / NSG allows 80/tcp inbound.
  3. Install the backup crons -- BOTH of them (see ops/README.md):
       ops/backup-db.sh     nightly local snapshot
       ops/backup-sync.sh   copy those snapshots OFF this host
     The second one is not optional. The last VM had nightly backups and lost
     every one of them, because they were on the disk that disappeared.
NEXT
if [ "${NEEDS_RELOGIN:-0}" = 1 ]; then
    echo
    echo "  Also: log out and back in so your user picks up the docker group."
fi
