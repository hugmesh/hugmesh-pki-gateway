#!/usr/bin/env bash
# Install Docker Engine + Compose v2 + buildx on Ubuntu (incl. WSL2).
# Run with: sudo bash scripts/install-docker.sh
# After it finishes, open a new shell (or run `newgrp docker`) so your user
# picks up the docker group membership, then `docker run --rm hello-world`.

set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "This script needs root. Re-run as: sudo bash $0" >&2
  exit 1
fi

TARGET_USER="${SUDO_USER:-${USER:-}}"
if [[ -z "$TARGET_USER" || "$TARGET_USER" == "root" ]]; then
  echo "Could not determine the non-root user to add to the docker group."
  echo "Set TARGET_USER explicitly: sudo TARGET_USER=youruser bash $0" >&2
  exit 1
fi

echo "==> Installing prerequisites"
apt-get update
apt-get install -y ca-certificates curl gnupg

echo "==> Adding Docker's official GPG key"
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

echo "==> Adding Docker apt repository"
ARCH="$(dpkg --print-architecture)"
CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
echo "deb [arch=${ARCH} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${CODENAME} stable" \
  > /etc/apt/sources.list.d/docker.list

echo "==> Installing Docker Engine + Compose v2 + buildx"
apt-get update
apt-get install -y \
  docker-ce \
  docker-ce-cli \
  containerd.io \
  docker-buildx-plugin \
  docker-compose-plugin

echo "==> Adding ${TARGET_USER} to the docker group"
usermod -aG docker "${TARGET_USER}"

echo "==> Starting the Docker daemon"
if pidof systemd >/dev/null 2>&1; then
  systemctl enable --now docker
else
  # WSL2 without systemd
  service docker start
fi

echo
echo "Done. Open a new shell (or run: newgrp docker) so ${TARGET_USER} picks up"
echo "the docker group, then verify with:"
echo "  docker version"
echo "  docker compose version"
echo "  docker run --rm hello-world"
