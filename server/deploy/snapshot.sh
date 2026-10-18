#!/bin/sh
# Snapshots the Nxtes data folder: a consistent copy of the database plus the blob and share files,
# hardlinked to the previous snapshot so unchanged files take no extra space.
set -eu
DATA=/opt/nxtes/data
DEST=/mnt/db/nxtes-snapshots
KEEP_DAYS=30

stamp=$(date +%Y-%m-%d_%H%M)
target="$DEST/$stamp"
mkdir -p "$target"
docker exec nxtes nxtes-server admin backup "/data/.snapshot-$stamp.db"
mv "$DATA/.snapshot-$stamp.db" "$target/nxtes.db"
for dir in blobs shares; do
    [ -d "$DATA/$dir" ] || continue
    if [ -d "$DEST/latest/$dir" ]; then
        rsync -a --link-dest="$DEST/latest/$dir" "$DATA/$dir/" "$target/$dir/"
    else
        rsync -a "$DATA/$dir/" "$target/$dir/"
    fi
done
ln -sfn "$target" "$DEST/latest"
find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +"$KEEP_DAYS" -exec rm -rf {} +
