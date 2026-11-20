import assert from "node:assert/strict";
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, randomUUID, sign } from "node:crypto";
import http from "node:http";

const baseUrl = process.env.API_BASE_URL ?? "http://127.0.0.1:8787";
const clientId = process.env.GOOGLE_WEB_CLIENT_ID ?? "504994159658-kelr3bllvp67caivm5afqg0gam8vu5vl.apps.googleusercontent.com";
const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const publicJwk = publicKey.export({ format: "jwk" });
const kid = "runtime-test-key";
const jwks = { keys: [{ ...publicJwk, kid, alg: "RS256", use: "sig" }] };
const jwksServer = http.createServer((_, response) => {
  response.setHeader("Content-Type", "application/json");
  response.end(JSON.stringify(jwks));
});
await new Promise(resolve => jwksServer.listen(8788, "127.0.0.1", resolve));
console.log(`JWKS test signer ready at http://127.0.0.1:8788; Worker must use GOOGLE_WEB_CLIENT_ID=${clientId}`);

const b64 = value => Buffer.from(value).toString("base64url");
const token = ({ sub, email, aud = clientId, tokenNonce = "nonce-ok", exp = Math.floor(Date.now() / 1000) + 600, tamper = false, tamperPayload = false }) => {
  const header = b64(JSON.stringify({ alg: "RS256", typ: "JWT", kid }));
  const payload = b64(JSON.stringify({ iss: "https://accounts.google.com", aud, sub, email, email_verified: true, exp, nonce: tokenNonce }));
  const input = `${header}.${payload}`;
  const signature = sign("RSA-SHA256", Buffer.from(input), privateKey);
  const signed = `${input}.${signature.toString("base64url")}`;
  if (tamperPayload) {
    const changedPayload = b64(JSON.stringify({ iss: "https://accounts.google.com", aud, sub, email, email_verified: true, exp, nonce: tokenNonce, tampered: true }));
    return `${header}.${changedPayload}.${signature.toString("base64url")}`;
  }
  if (tamper) return `${signed.slice(0, -1)}${signed.endsWith("A") ? "B" : "A"}`;
  return signed;
};
const request = async (path, { method = "GET", token: accessToken, body, label = path, timeoutMs = 5000 } = {}) => {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      headers: { ...(body ? { "Content-Type": "application/json" } : {}), ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal: controller.signal
    });
    return { response, result: response.status === 204 ? null : await response.json() };
  } catch (error) {
    throw new Error(`${label} timed out or failed after ${timeoutMs}ms: ${error.message}`, { cause: error });
  } finally {
    clearTimeout(timer);
  }
};
const google = (options = {}) => ({ idToken: token({ sub: options.sub ?? `sub-${Date.now()}`, email: options.email ?? `google-${Date.now()}@example.com`, ...options }), nonce: options.requestNonce ?? options.tokenNonce ?? "nonce-ok" });
try {
  const badSignature = await request("/auth/google", { method: "POST", label: "bad signature", body: google({ tamperPayload: true }) });
  assert.equal(badSignature.response.status, 401);
  const badAudience = await request("/auth/google", { method: "POST", label: "bad audience", body: google({ aud: "wrong-audience" }) });
  assert.equal(badAudience.response.status, 401);
  const badNonce = await request("/auth/google", { method: "POST", label: "bad nonce", body: google({ tokenNonce: "token-nonce", requestNonce: "request-nonce" }) });
  assert.equal(badNonce.response.status, 401);

  // Every invocation must use a fresh Google identity. The local D1 database is
  // intentionally preserved between smoke runs, so a fixed identity would be a
  // returning user and would no longer exercise the 201 create path.
  const runStamp = `${Date.now()}-${randomUUID()}`;
  const newSub = `sub-new-${runStamp}`;
  const newEmail = `new-google-${runStamp}@example.com`;
  const duplicateSub = `sub-duplicate-${runStamp}`;
  const duplicateEmail = `duplicate-${runStamp}@example.com`;
  const linkedSub = `sub-linked-${runStamp}`;
  const otherEmail = `other-${runStamp}@example.com`;

  const newUser = google({ sub: newSub, email: newEmail });
  const created = await request("/auth/google", { method: "POST", body: newUser });
  assert.equal(created.response.status, 201, JSON.stringify(created.result));
  const passwordLogin = await request("/auth/login", { method: "POST", body: { email: newEmail, password: "AnyPassword123" } });
  assert.equal(passwordLogin.response.status, 401);
  const returning = await request("/auth/google", { method: "POST", body: newUser });
  assert.equal(returning.response.status, 200);
  assert.equal(returning.result.user.email, newEmail);

  const passwordUser = await request("/auth/register", { method: "POST", body: { email: duplicateEmail, password: "Password123" } });
  assert.equal(passwordUser.response.status, 201);
  const duplicate = await request("/auth/google", { method: "POST", body: google({ sub: duplicateSub, email: duplicateEmail }) });
  assert.equal(duplicate.response.status, 409);
  assert.equal(duplicate.result.error.code, "ACCOUNT_LINK_REQUIRED");

  const linked = await request("/auth/google/link", { method: "POST", token: passwordUser.result.accessToken, body: google({ sub: linkedSub, email: duplicateEmail }) });
  assert.equal(linked.response.status, 200, JSON.stringify(linked.result));
  const linkedAgain = await request("/auth/google", { method: "POST", body: google({ sub: linkedSub, email: duplicateEmail }) });
  assert.equal(linkedAgain.response.status, 200);
  const other = await request("/auth/register", { method: "POST", body: { email: otherEmail, password: "Password123" } });
  const conflict = await request("/auth/google/link", { method: "POST", token: other.result.accessToken, body: google({ sub: linkedSub, email: other.result.user.email }) });
  assert.equal(conflict.response.status, 409);
  assert.equal(conflict.result.error.code, "GOOGLE_IDENTITY_ALREADY_LINKED");
  console.log("Google smoke passed: signature, audience, nonce, new/returning user, duplicate email, link, identity conflict, Google-only password rejection.");
} finally {
  jwksServer.close();
}
