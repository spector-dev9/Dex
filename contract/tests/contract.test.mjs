import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");

function readJson(relative) {
  return JSON.parse(readFileSync(join(root, relative), "utf8"));
}

const doc = readJson("contract/openapi/opencode-1.19.3.json");
const inventory = readJson("contract/endpoints.json");
const manifest = readJson("runtime/manifest.json");
const noAuth = readJson("tests/spikes/results/raw/no-auth-summary.json");
const withAuth = readJson("tests/spikes/results/raw/with-auth-summary.json");
const sse = readJson("tests/spikes/results/raw/sse-no-auth-summary.json");

const DOC_SHA256 =
  "eebb56042880e07c354e0ad0a31f70aab45f536adf5b40b6b1fd5abf4663417b";

test("pinned /doc snapshot is stable", () => {
  const bytes = readFileSync(join(root, "contract/openapi/opencode-1.19.3.json"));
  assert.equal(createHash("sha256").update(bytes).digest("hex"), DOC_SHA256);
});

test("doc reports OpenAPI 3.1.0 with the expected path count", () => {
  assert.equal(doc.openapi, "3.1.0");
  assert.equal(doc.info.title, "opencode");
  assert.equal(inventory.pathCount, 162);
  assert.equal(Object.keys(doc.paths).length, inventory.pathCount);
});

test("every Section 4.1 endpoint exists in the pinned spec", () => {
  assert.equal(inventory.required.length, 36);
  assert.deepEqual(inventory.missingRequired, []);
  for (const entry of inventory.required) {
    assert.ok(
      doc.paths[entry.path]?.[entry.method.toLowerCase()],
      `${entry.method} ${entry.path} missing from /doc`,
    );
  }
});

test("PTY routes are documented in the pinned spec (F3 correction)", () => {
  const pty = Object.keys(doc.paths).filter(
    (path) => path === "/pty" || path.startsWith("/pty/"),
  );
  assert.deepEqual(pty.sort(), [
    "/pty",
    "/pty/shells",
    "/pty/{ptyID}",
    "/pty/{ptyID}/connect",
    "/pty/{ptyID}/connect-token",
  ]);
  assert.equal(inventory.pty.documentedInPinnedSpec, true);
  assert.equal(inventory.pty.routes.length, 9);
});

test("the pinned spec exposes an /api/* surface not in Section 4.1", () => {
  assert.equal(inventory.apiV2.count, 51);
  assert.ok(doc.paths["/api/pty/{ptyID}/connect-token"]);
});

test("without a password every HTTP route is open (F5 confirmed)", () => {
  assert.equal(noAuth.passwordConfigured, false);
  assert.equal(noAuth.health.healthy, true);
  for (const [route, matrix] of Object.entries(noAuth.authMatrix)) {
    if (route === "event") continue;
    assert.equal(matrix.noAuth, 200, `${route} must be open without a password`);
  }
  assert.equal(noAuth.authMatrix.pty.noAuth, 200);
});

test("OPENCODE_SERVER_PASSWORD enforces Basic auth for user opencode", () => {
  assert.equal(withAuth.passwordConfigured, true);
  assert.equal(withAuth.health.healthy, true);
  for (const [route, matrix] of Object.entries(withAuth.authMatrix)) {
    assert.equal(matrix.noAuth, 401, `${route} must reject unauthenticated calls`);
    assert.equal(matrix.wrongPassword, 401, `${route} must reject a wrong password`);
    assert.equal(matrix.basicEmptyUser, 401, `${route} must reject an empty user`);
    assert.equal(matrix.bearer, 401, `${route} must reject bearer tokens`);
    if (route === "event") {
      assert.equal(matrix.basicOpencode, "error:TimeoutError", "SSE stays open");
    } else {
      assert.equal(matrix.basicOpencode, 200, `${route} must accept Basic auth`);
    }
  }
});

test("health checks are also protected when a password is set", () => {
  assert.equal(withAuth.authMatrix.health.noAuth, 401);
  assert.equal(withAuth.authMatrix.health.basicOpencode, 200);
});

test("SSE emits server.connected then heartbeats every ~10 s", () => {
  const first = sse.sse.first;
  assert.equal(first.status, 200);
  assert.deepEqual(first.eventTypes, [
    "server.connected",
    "server.heartbeat",
    "server.heartbeat",
  ]);
  assert.equal(first.closedByServer, false);
  assert.equal(first.chunks.length, 3);
  const times = first.chunks.map((chunk) => chunk.at);
  for (let index = 1; index < times.length; index += 1) {
    const interval = times[index] - times[index - 1];
    assert.ok(interval >= 9 && interval <= 11, `heartbeat interval ${interval}s`);
  }
});

test("SSE reconnect delivers a fresh server.connected event", () => {
  assert.equal(sse.sse.reconnect.status, 200);
  assert.ok(sse.sse.reconnect.eventTypes.includes("server.connected"));
  assert.equal(sse.sse.reconnectDeliveredNewConnectedEvent, true);
});

test("runtime manifest pins 1.19.3 and refuses unknown majors", () => {
  assert.equal(manifest.version, "1.19.3");
  assert.equal(
    manifest.artifact.url,
    "https://github.com/anomalyco/opencode/releases/download/v1.19.3/opencode-linux-arm64-musl.tar.gz",
  );
  assert.equal(
    manifest.artifact.sha256,
    "39c9ff1ea47fd59752613079f97bef40a2d14c30bc6bb5772a91d9d708e2d0b3",
  );
  assert.equal(
    manifest.binarySha256,
    "37c7de554c2ba7bf456e4fe6faf7705141b0ccb3a7b1dd6e1046d52cd8269ecd",
  );
  assert.equal(manifest.unknownMajorVersions, "refused");
  assert.ok(manifest.required.includes("opencode"));
  assert.ok(manifest.runtimeLibraries.includes("libstdc++.so.6"));
  assert.equal(manifest.alpineRuntimePackages.length, 2);
  assert.equal(inventory.pinnedVersion, manifest.version);
});
