#!/usr/bin/env bash
set -euo pipefail
source ../buildScript/lib/core/get_source_env.sh
export GOPATH="${GOPATH:-$(go env GOPATH)}"
if [ ! -d gomobile ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/gomobile.git gomobile
fi
git -C gomobile checkout "$COMMIT_GOMOBILE"
python3 ../buildScript/lib/core/patch_loader.py gomobile/bind/java/Seq.java
(cd gomobile && go build -o "$GOPATH/bin/gomobile-matsuri" ./cmd/gomobile && go build -o "$GOPATH/bin/gobind-matsuri" ./cmd/gobind)
GOBIND=gobind-matsuri "$GOPATH/bin/gomobile-matsuri" init
go mod edit -droprequire github.com/sagernet/gvisor
go mod tidy
