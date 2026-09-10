#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout "$SING_BOX_REPO" sing-box
fi
pushd sing-box
git fetch --depth 1 "$SING_BOX_REPO" "$COMMIT_SING_BOX"
git checkout --detach "$COMMIT_SING_BOX"
if [ "$CORE_CHANNEL" = "preview" ]; then
  git apply --exclude=box.go "$SRC_ROOT/buildScript/lib/core/patches/android-preview.patch"
  python3 - <<'PY'
from pathlib import Path
p=Path('box.go')
s=p.read_text()
old='experimentalOptions.CacheFile.Enabled || options.PlatformLogWriter != nil'
assert old in s
p.write_text(s.replace(old,'experimentalOptions.CacheFile.Enabled'))
PY
fi
git apply "$SRC_ROOT/buildScript/lib/core/patches/core-compat.patch"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
