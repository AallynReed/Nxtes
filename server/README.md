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
| `NXTES_DATA` | `data` | Folder for the database and blobs (`/data` in the image) |
| `NXTES_PUBLIC_URL` | `http://<bind>` | Address users reach the server at, used in email links |
| `NXTES_SMTP_URL` | unset | For example `smtp://mail.example.com:587?tls=required`, `smtps://user:pass@host`. Unset, emails are only logged |
| `NXTES_MAIL_FROM` | `Nxtes <nxtes@localhost>` | Sender of account emails |
| `NXTES_REQUIRE_EMAIL_VERIFICATION` | `true` | Whether accounts must confirm their address before signing in |
| `NXTES_ALLOW_REGISTRATION` | `true` | Whether anyone can create an account |
| `NXTES_DEFAULT_QUOTA_MB` | `2048` | Storage per account unless set with `admin set-quota` |
| `NXTES_MAX_DEVICES` | `0` | Devices per account; 0 means no limit |
| `NXTES_MAX_BLOB_MB` | `1024` | Largest single file |

## Admin commands

Run inside the container with `docker exec nxtes nxtes-server admin <command>`:

- `create-user <email> <password>`: an account that's already verified
- `verify-user <email>`
- `set-quota <email> <megabytes>` (0 goes back to the default)
- `list-users`
- `delete-user <email>`
- `collect-blobs`: delete files no document uses (this also runs hourly)

The API is described in [docs/plan.md](../docs/plan.md#sync-api-v1).
