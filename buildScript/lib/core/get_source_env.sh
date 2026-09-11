export CORE_CHANNEL="${CORE_CHANNEL:-stable}"
export CORE_REVISION=3
export CORE_TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
if [ "$CORE_CHANNEL" = "stable" ]; then
  export COMMIT_SING_BOX="0bd0381c146042237abacc831249a90ab753ef8e"
  export SING_BOX_REPO="https://github.com/MatsuriDayo/sing-box.git"
  export CORE_VERSION="1.14.0"
elif [ "$CORE_CHANNEL" = "preview" ]; then
  export COMMIT_SING_BOX="b4b5af50b37dbdb3015e7a4b6b1c08a2dee80dbe"
  export SING_BOX_REPO="https://github.com/SagerNet/sing-box.git"
  export CORE_VERSION="1.15.0-alpha.2"
  export CORE_TAGS="$CORE_TAGS,core_preview"
else
  echo "Unknown CORE_CHANNEL" >&2
  exit 1
fi
export COMMIT_LIBNEKO="1c47a3af71990a7b2192e03292b4d246c308ef0b"
export COMMIT_GOMOBILE="17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f"
