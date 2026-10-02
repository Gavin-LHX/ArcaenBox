#!/bin/bash

set -euo pipefail

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

get_latest_release() {
  local tag
  if [ -n "${GH_TOKEN:-}" ]; then
    tag=$(gh api "repos/$1/releases/latest" --jq '.tag_name')
  else
    tag=$(curl --fail --silent --show-error --retry 3 "https://api.github.com/repos/$1/releases/latest" |
      python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"])')
  fi
  # Never turn an API error or rate limit response into an empty download URL.
  [[ "$tag" =~ ^[A-Za-z0-9._-]+$ ]] && [ "$tag" != null ] || return 1
  printf '%s' "$tag"
}

####
VERSION_GEOIP=`get_latest_release "SagerNet/sing-geoip"`
echo VERSION_GEOIP=$VERSION_GEOIP
echo -n $VERSION_GEOIP > geoip.version.txt
curl --retry 3 -fLSsO "https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db"
xz -9 geoip.db

####
VERSION_GEOSITE=`get_latest_release "SagerNet/sing-geosite"`
echo VERSION_GEOSITE=$VERSION_GEOSITE
echo -n $VERSION_GEOSITE > geosite.version.txt
curl --retry 3 -fLSsO "https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db"
xz -9 geosite.db
