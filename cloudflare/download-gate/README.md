# Legado Download Gate

Cloudflare Worker download gate for a private GitHub Release APK. The Worker asks for a download key, then uses a GitHub token stored as a Cloudflare secret to stream the ARM64 APK from the private release.

## Configure

The default `wrangler.toml` points to:

- Owner: `Rimchars`
- Repo: `legado-private-armv8-release`
- Release: latest release
- Asset: `arm64-v8a.*\.apk$`

Create a GitHub fine-grained token with read-only access to the private repository contents. Store both secrets in Cloudflare:

```powershell
cd cloudflare/download-gate
npm install
npx wrangler secret put DOWNLOAD_KEY
npx wrangler secret put GITHUB_TOKEN
```

`DOWNLOAD_KEY` is the password users type on the download page. `GITHUB_TOKEN` must not be shared with download users.

## Run Locally

Create `.dev.vars` for local testing only:

```text
DOWNLOAD_KEY=your-download-key
GITHUB_TOKEN=github_pat_xxx
```

Then start the Worker:

```powershell
npm run dev
```

Open the local Wrangler URL and submit the download key.

## Deploy

```powershell
npm run deploy
```

After deploy, the Worker URL is the private download page. GitHub Release remains private; users only receive the APK stream after entering `DOWNLOAD_KEY`.

The Android app can use the Worker root URL as its internal beta update address. It calls:

- `GET /latest` with header `X-Download-Key: <DOWNLOAD_KEY>` for metadata.
- `GET /download` with header `X-Download-Key: <DOWNLOAD_KEY>` for the APK.

## Notes

- Do not put `DOWNLOAD_KEY` in the URL. The web form sends it via `POST`; the app sends it via the `X-Download-Key` header.
- The Worker caches the selected release asset metadata in memory for 60 seconds, not the APK file.
- For heavier public distribution, put the APK in Cloudflare R2 and let the Worker generate short-lived signed URLs.
