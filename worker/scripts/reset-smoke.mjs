import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import http from "node:http";

const inbox = [];
const server = http.createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/v3/smtp/email") { response.writeHead(404).end(); return; }
  assert.equal(request.headers["api-key"], "local-test-brevo-key");
  let raw = "";
  for await (const chunk of request) raw += chunk;
  inbox.push(JSON.parse(raw));
  response.writeHead(201, { "Content-Type": "application/json" }).end('{"messageId":"local-test"}');
});
await new Promise(resolve => server.listen(8789, "127.0.0.1", resolve));

const base = process.env.API_BASE_URL ?? "http://127.0.0.1:8787";
async function call(path, body, token) {
  const response = await fetch(`${base}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body)
  });
  return { status: response.status, body: await response.json() };
}

try {
  const email = `reset-${randomUUID()}@example.com`;
  const oldPassword = "Old-Pass-12345";
  const newPassword = "New-Pass-67890";
  const registered = await call("/auth/register", { email, password: oldPassword });
  assert.equal(registered.status, 201);
  assert.equal((await call("/auth/password-reset/request", { email })).status, 202);
  assert.equal(inbox.length, 1);
  assert.deepEqual(inbox[0].sender, { name: "ZPlanner", email: "sender@example.com" });
  assert.deepEqual(inbox[0].to, [{ email }]);
  const code = inbox[0].textContent.match(/\b\d{8}\b/)?.[0];
  assert.ok(code);
  assert.equal((await call("/auth/password-reset/request", { email })).status, 202);
  assert.equal(inbox.length, 1, "Cooldown must prevent duplicate emails");
  const wrong = code === "00000000" ? "11111111" : "00000000";
  assert.equal((await call("/auth/password-reset/confirm", { email, code: wrong, newPassword })).status, 400);
  assert.equal((await call("/auth/password-reset/confirm", { email, code, newPassword })).status, 200);
  const oldSession = await fetch(`${base}/auth/me`, { headers: { Authorization: `Bearer ${registered.body.accessToken}` } });
  assert.equal(oldSession.status, 401, "Existing sessions must be revoked");
  assert.equal((await call("/auth/login", { email, password: oldPassword })).status, 401);
  assert.equal((await call("/auth/login", { email, password: newPassword })).status, 200);
  assert.equal((await call("/auth/password-reset/confirm", { email, code, newPassword })).status, 400);
  const lockedEmail = `reset-lock-${randomUUID()}@example.com`;
  assert.equal((await call("/auth/register", { email: lockedEmail, password: oldPassword })).status, 201);
  assert.equal((await call("/auth/password-reset/request", { email: lockedEmail })).status, 202);
  const lockedCode = inbox.at(-1).textContent.match(/\b\d{8}\b/)?.[0];
  assert.ok(lockedCode);
  const wrongLockedCode = lockedCode === "00000000" ? "11111111" : "00000000";
  for (let i = 0; i < 5; i++) assert.equal((await call("/auth/password-reset/confirm", { email: lockedEmail, code: wrongLockedCode, newPassword })).status, 400);
  assert.equal((await call("/auth/password-reset/confirm", { email: lockedEmail, code: lockedCode, newPassword })).status, 400);
  console.log("Password reset smoke passed: email delivery, cooldown, wrong/one-time code, lockout, session revoke, new password.");
} finally {
  server.close();
}
