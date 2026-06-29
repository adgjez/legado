interface Env {
  DOWNLOAD_KEY: string;
  GITHUB_TOKEN: string;
  GITHUB_OWNER: string;
  GITHUB_REPO: string;
  RELEASE_TAG?: string;
  ASSET_REGEX?: string;
  APP_TITLE?: string;
}

interface GitHubAsset {
  name: string;
  size: number;
  url: string;
  browser_download_url: string;
  content_type: string;
}

interface GitHubRelease {
  tag_name: string;
  name: string | null;
  assets: GitHubAsset[];
}

interface SelectedAsset {
  releaseTag: string;
  name: string;
  size: number;
  apiUrl: string;
  contentType: string;
}

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

let cachedAsset: { value: SelectedAsset; expiresAt: number } | null = null;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return htmlResponse(renderHome(env));
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return jsonResponse({ ok: true });
    }

    if (request.method === "GET" && url.pathname === "/latest") {
      return handleLatest(request, env);
    }

    if (
      (request.method === "POST" || request.method === "GET") &&
      url.pathname === "/download"
    ) {
      return handleDownload(request, env);
    }

    return new Response("Not found", { status: 404 });
  },
};

async function handleDownload(request: Request, env: Env): Promise<Response> {
  if (!env.DOWNLOAD_KEY || !env.GITHUB_TOKEN) {
    return jsonResponse(
      { error: "Worker secrets DOWNLOAD_KEY and GITHUB_TOKEN are required." },
      500,
    );
  }

  if (!(await verifyDownloadKey(request, env))) {
    return htmlResponse(renderHome(env, "下载密钥不正确。"), 401);
  }

  try {
    const asset = await getSelectedAsset(env);
    const githubResponse = await fetch(asset.apiUrl, {
      headers: githubHeaders(env.GITHUB_TOKEN, "application/octet-stream"),
      redirect: "manual",
    });

    if (isRedirect(githubResponse.status)) {
      const location = githubResponse.headers.get("location");
      if (!location) {
        return htmlResponse(renderHome(env, "GitHub 没有返回下载地址。"), 502);
      }
      return streamDownload(await fetch(location), asset);
    }

    return streamDownload(githubResponse, asset);
  } catch (error) {
    console.error(error);
    return htmlResponse(renderHome(env, "暂时无法获取 Release 文件，请稍后重试。"), 502);
  }
}

async function handleLatest(request: Request, env: Env): Promise<Response> {
  if (!env.DOWNLOAD_KEY || !env.GITHUB_TOKEN) {
    return jsonResponse(
      { error: "Worker secrets DOWNLOAD_KEY and GITHUB_TOKEN are required." },
      500,
    );
  }

  if (!(await verifyDownloadKey(request, env))) {
    return jsonResponse({ error: "Invalid download key." }, 401);
  }

  try {
    const asset = await getSelectedAsset(env);
    const publicUrl = new URL(request.url);
    publicUrl.pathname = "/download";
    publicUrl.search = "";
    return jsonResponse({
      tagName: asset.releaseTag,
      versionName: versionNameFromFileName(asset.name) ?? asset.releaseTag,
      versionCode: versionCodeFromFileName(asset.name),
      updateLog: `内测版 ${asset.releaseTag}`,
      downloadUrl: publicUrl.toString(),
      fileName: asset.name,
      size: asset.size,
    });
  } catch (error) {
    console.error(error);
    return jsonResponse({ error: "Release lookup failed." }, 502);
  }
}

async function getSelectedAsset(env: Env): Promise<SelectedAsset> {
  const now = Date.now();
  if (cachedAsset && cachedAsset.expiresAt > now) {
    return cachedAsset.value;
  }

  const owner = requiredVar(env.GITHUB_OWNER, "GITHUB_OWNER");
  const repo = requiredVar(env.GITHUB_REPO, "GITHUB_REPO");
  const releaseTag = env.RELEASE_TAG?.trim();
  const releasePath = releaseTag
    ? `releases/tags/${encodeURIComponent(releaseTag)}`
    : "releases/latest";
  const releaseUrl = `https://api.github.com/repos/${owner}/${repo}/${releasePath}`;

  const response = await fetch(releaseUrl, {
    headers: githubHeaders(env.GITHUB_TOKEN, "application/vnd.github+json"),
  });
  if (!response.ok) {
    throw new Error(`GitHub release lookup failed: ${response.status}`);
  }

  const release = (await response.json()) as GitHubRelease;
  const assetRegex = new RegExp(env.ASSET_REGEX || "arm64-v8a.*\\.apk$", "i");
  const asset = release.assets.find((item) => assetRegex.test(item.name));
  if (!asset) {
    throw new Error(`No release asset matched ${assetRegex.source}`);
  }

  const selected: SelectedAsset = {
    releaseTag: release.tag_name,
    name: asset.name,
    size: asset.size,
    apiUrl: asset.url,
    contentType: asset.content_type || "application/vnd.android.package-archive",
  };
  cachedAsset = { value: selected, expiresAt: now + 60_000 };
  return selected;
}

async function streamDownload(response: Response, asset: SelectedAsset): Promise<Response> {
  if (!response.ok || !response.body) {
    return jsonResponse({ error: `GitHub asset download failed: ${response.status}` }, 502);
  }

  return new Response(response.body, {
    status: 200,
    headers: {
      "content-type": asset.contentType,
      "content-disposition": `attachment; filename="${sanitizeFilename(asset.name)}"`,
      "cache-control": "private, no-store",
      "x-release-tag": asset.releaseTag,
      "x-content-type-options": "nosniff",
      ...(asset.size > 0 ? { "content-length": String(asset.size) } : {}),
    },
  });
}

function githubHeaders(token: string, accept: string): HeadersInit {
  return {
    accept,
    authorization: `Bearer ${token}`,
    "user-agent": "legado-download-gate",
    "x-github-api-version": "2022-11-28",
  };
}

function constantTimeEqual(left: string, right: string): boolean {
  const encoder = new TextEncoder();
  const leftBytes = encoder.encode(left);
  const rightBytes = encoder.encode(right);
  const maxLength = Math.max(leftBytes.length, rightBytes.length);
  let diff = leftBytes.length ^ rightBytes.length;

  for (let index = 0; index < maxLength; index += 1) {
    diff |= (leftBytes[index] ?? 0) ^ (rightBytes[index] ?? 0);
  }

  return diff === 0;
}

async function verifyDownloadKey(request: Request, env: Env): Promise<boolean> {
  const url = new URL(request.url);
  let providedKey = request.headers.get("x-download-key") || url.searchParams.get("key") || "";
  if (!providedKey && request.method === "POST") {
    const form = await request.formData();
    providedKey = String(form.get("key") ?? "");
  }
  return constantTimeEqual(providedKey, env.DOWNLOAD_KEY);
}

function versionNameFromFileName(name: string): string | null {
  return /^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$/i.exec(name)?.[1] ?? null;
}

function versionCodeFromFileName(name: string): number {
  const raw = /^.+?_.+?_([^_]+)(?:_(\d+))?\.apk$/i.exec(name)?.[2];
  return raw ? Number.parseInt(raw, 10) || 0 : 0;
}

function requiredVar(value: string | undefined, name: string): string {
  if (!value?.trim()) {
    throw new Error(`${name} is required`);
  }
  return value.trim();
}

function isRedirect(status: number): boolean {
  return status >= 300 && status < 400;
}

function sanitizeFilename(name: string): string {
  return name.replace(/["\\\r\n]/g, "_");
}

function htmlResponse(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
    },
  });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: JSON_HEADERS,
  });
}

function renderHome(env: Env, error?: string): string {
  const title = escapeHtml(env.APP_TITLE || "Private APK Download");
  const owner = escapeHtml(env.GITHUB_OWNER || "");
  const repo = escapeHtml(env.GITHUB_REPO || "");
  const tag = escapeHtml(env.RELEASE_TAG?.trim() || "latest");
  const errorHtml = error ? `<p class="error">${escapeHtml(error)}</p>` : "";

  return `<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title}</title>
    <style>
      :root {
        color-scheme: light dark;
        font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      }
      body {
        min-height: 100vh;
        margin: 0;
        display: grid;
        place-items: center;
        background: #f4f5f7;
        color: #16181d;
      }
      main {
        width: min(420px, calc(100vw - 32px));
        box-sizing: border-box;
        padding: 28px;
        border: 1px solid #d9dde5;
        border-radius: 8px;
        background: #ffffff;
        box-shadow: 0 16px 48px rgb(20 24 32 / 10%);
      }
      h1 {
        margin: 0 0 8px;
        font-size: 22px;
        line-height: 1.25;
        letter-spacing: 0;
      }
      .meta {
        margin: 0 0 24px;
        color: #5c6675;
        font-size: 13px;
        line-height: 1.5;
        overflow-wrap: anywhere;
      }
      label {
        display: block;
        margin-bottom: 8px;
        font-size: 14px;
        font-weight: 600;
      }
      input {
        width: 100%;
        height: 44px;
        box-sizing: border-box;
        border: 1px solid #b9c0cc;
        border-radius: 6px;
        padding: 0 12px;
        font: inherit;
        background: transparent;
      }
      button {
        width: 100%;
        height: 44px;
        margin-top: 16px;
        border: 0;
        border-radius: 6px;
        background: #1565c0;
        color: #fff;
        font: inherit;
        font-weight: 700;
        cursor: pointer;
      }
      button:hover {
        background: #0d56a8;
      }
      .error {
        margin: 0 0 16px;
        padding: 10px 12px;
        border-radius: 6px;
        background: #feecec;
        color: #a11212;
        font-size: 14px;
      }
      @media (prefers-color-scheme: dark) {
        body {
          background: #101318;
          color: #f2f4f8;
        }
        main {
          border-color: #303846;
          background: #181d24;
          box-shadow: none;
        }
        .meta {
          color: #aab4c2;
        }
        input {
          border-color: #586274;
          color: #f2f4f8;
        }
        .error {
          background: #3a1717;
          color: #ffb8b8;
        }
      }
    </style>
  </head>
  <body>
    <main>
      <h1>${title}</h1>
      <p class="meta">${owner}/${repo} · ${tag}</p>
      ${errorHtml}
      <form method="post" action="/download">
        <label for="key">下载密钥</label>
        <input id="key" name="key" type="password" autocomplete="current-password" required autofocus>
        <button type="submit">下载 ARM64 APK</button>
      </form>
    </main>
  </body>
</html>`;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}
