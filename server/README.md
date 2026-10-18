# Nxtes server

The optional sync server for Nxtes: accounts confirmed by email, devices, and sync of documents,
folders and their files. It stores what the app sends and never reads note contents.

## Running it

```powershell
cargo run --release
```

Or with Docker, using the image CI publishes:

```powershell
docker run -d -p 8080:8080 -v nxtes-data:/data -e NXTES_PUBLIC_URL=https://notes.example.com ghcr.io/aallynreed/nxtes-server:latest
```

`deploy/docker-compose.yml` is the official instance's stack.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `NXTES_BIND` | `127.0.0.1:8080` | Address to listen on (`0.0.0.0:8080` in the image) |
| `NXTES_DATA` | `data` | Folder for the database, blobs and shared PDFs (`/data` in the image) |
| `NXTES_PUBLIC_URL` | `http://<bind>` | Address users reach the server at, used in email links |
| `NXTES_SMTP_URL` | unset | For example `smtp://mail.example.com:587?tls=required`, `smtps://user:pass@host`. Unset, emails are only logged |
| `NXTES_MAIL_FROM` | `Nxtes <nxtes@localhost>` | Sender of account emails |
| `NXTES_REQUIRE_EMAIL_VERIFICATION` | `true` | Whether accounts must confirm their address before signing in |
| `NXTES_ALLOW_REGISTRATION` | `true` | Whether anyone can create an account |
| `NXTES_DEFAULT_QUOTA_MB` | `2048` | Storage per account unless set with `admin set-quota` |
| `NXTES_MAX_DEVICES` | `0` | Devices per account; 0 means no limit |
| `NXTES_MAX_BLOB_MB` | `1024` | Largest single file or shared PDF |
| `NXTES_HISTORY_DAYS` | `30` | Days earlier versions of documents and folders, and deleted ones, are kept for restoring. They count toward storage. 0 keeps none |
| `NXTES_ASSETS` | unset | Folder with the web viewer's `pdfjs/` files (`/usr/share/nxtes` in the image). Unset, shared-link pages load pdf.js from jsDelivr |

## Admin commands

Run inside the container with `docker exec nxtes nxtes-server admin <command>`:

- `create-user <email> <password>`: an account that's already verified
- `verify-user <email>`
- `set-quota <email> <megabytes>` (0 goes back to the default)
- `list-users`
- `delete-user <email>`
- `collect-blobs`: delete files no document uses (this also runs hourly)
- `history <email>`: documents and folders with earlier versions, and deleted ones that can come back
- `restore <email> <id> [revision]`: bring one back to an earlier revision, by default the last one before it changed or was deleted
- `backup <path>`: write a consistent copy of the database while the server runs

## Snapshots and recovery

Two layers keep data recoverable:

- **Version history.** Every change to a document or folder keeps the version it replaced, with its
  files, for `NXTES_HISTORY_DAYS`. Deleting keeps the last version too. Restore one from the app
  or with `admin restore`; the restore is a new revision that syncs to every device.
- **Snapshots of the whole data folder.** `deploy/snapshot.sh` copies the database (with
  `admin backup`) and the blob and share files into a dated folder, hardlinking files that didn't
  change since the last snapshot. `deploy/nxtes-snapshot.timer` runs it nightly; snapshots older
  than 30 days are removed.

To roll the whole server back to a snapshot:

```sh
cd /opt/nxtes && docker compose stop
rm -f data/nxtes.db-wal data/nxtes.db-shm
cp /mnt/db/nxtes-snapshots/<stamp>/nxtes.db data/nxtes.db
rsync -a --delete /mnt/db/nxtes-snapshots/<stamp>/blobs/ data/blobs/
rsync -a --delete /mnt/db/nxtes-snapshots/<stamp>/shares/ data/shares/
chown -R 10001:10001 data && docker compose start
```

## Shared links

The app can upload an exported PDF and get a link anyone can open at `/s/<token>`, without an
account. The page draws the PDF with pdf.js and offers a download. Links can expire after a set
number of days or last until the owner removes them; expired ones are deleted hourly. Shared PDFs
count toward the account's storage.

The API is described in [docs/plan.md](../docs/plan.md#sync-api-v1).
