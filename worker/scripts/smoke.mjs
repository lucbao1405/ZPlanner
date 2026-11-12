import assert from "node:assert/strict";

const baseUrl = process.env.API_BASE_URL ?? "http://127.0.0.1:8787";
const stamp = `${Date.now()}-${Math.random().toString(16).slice(2)}`;

async function request(path, { method = "GET", token, body } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  const result = response.status === 204 ? null : await response.json();
  return { response, result };
}

const health = await request("/health");
assert.equal(health.response.status, 200);

const register = await request("/auth/register", {
  method: "POST",
  body: { email: `smoke-${stamp}@example.com`, password: "S3cure-Local-Only" }
});
assert.equal(register.response.status, 201, JSON.stringify(register.result));
const tokenA = register.result.accessToken;

const registerB = await request("/auth/register", {
  method: "POST",
  body: { email: `smoke-b-${stamp}@example.com`, password: "S3cure-Local-Only" }
});
assert.equal(registerB.response.status, 201, JSON.stringify(registerB.result));
const tokenB = registerB.result.accessToken;

const created = await request("/tasks", {
  method: "POST",
  token: tokenA,
  body: {
    title: "Kiểm tra MVP",
    description: "Dữ liệu smoke test cục bộ",
    date: "2026-11-08",
    startTime: "09:00",
    endTime: "10:00",
    priority: "high",
    status: "todo"
  }
});
assert.equal(created.response.status, 201, JSON.stringify(created.result));
const taskId = created.result.task.id;

const ownTasks = await request("/tasks?date=2026-11-08", { token: tokenA });
assert.equal(ownTasks.response.status, 200);
assert.equal(ownTasks.result.tasks.length, 1);
assert.equal(ownTasks.result.tasks[0].id, taskId);

const otherTasks = await request("/tasks?date=2026-11-08", { token: tokenB });
assert.equal(otherTasks.response.status, 200);
assert.equal(otherTasks.result.tasks.length, 0, "A second user must not see the first user's task");

const updated = await request(`/tasks/${taskId}`, {
  method: "PUT",
  token: tokenA,
  body: { ...created.result.task, title: "MVP đã cập nhật", status: "done" }
});
assert.equal(updated.response.status, 200, JSON.stringify(updated.result));
assert.equal(updated.result.task.title, "MVP đã cập nhật");
assert.equal(updated.result.task.status, "done");

const forbiddenUpdate = await request(`/tasks/${taskId}`, {
  method: "PUT",
  token: tokenB,
  body: { ...created.result.task, title: "Không được sửa" }
});
assert.equal(forbiddenUpdate.response.status, 404);

const invalidTime = await request("/tasks", {
  method: "POST",
  token: tokenA,
  body: { title: "Bad time", date: "2026-11-08", startTime: "10:00", endTime: "09:00" }
});
assert.equal(invalidTime.response.status, 400);

const deleted = await request(`/tasks/${taskId}`, { method: "DELETE", token: tokenA });
assert.equal(deleted.response.status, 204);

const loggedOut = await request("/auth/logout", { method: "POST", token: tokenA });
assert.equal(loggedOut.response.status, 204);
const afterLogout = await request("/auth/me", { token: tokenA });
assert.equal(afterLogout.response.status, 401);

console.log("Worker smoke passed: health, register, owner isolation, CRUD, validation, logout.");
