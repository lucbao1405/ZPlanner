const PASSWORD_ITERATIONS = 310_000;
const TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;
const encoder = new TextEncoder();

interface Env {
  DB: D1Database;
  AUTH_SECRET: string;
}

interface UserRow {
  id: string;
  email: string;
  password_hash: string;
  password_salt: string;
  password_iterations: number;
}

interface TaskInput {
  title: string;
  description: string;
  date: string;
  startTime: string;
  endTime: string;
  priority: "low" | "medium" | "high";
  status: "todo" | "in_progress" | "done";
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
  }
}

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  "Access-Control-Allow-Headers": "Authorization, Content-Type",
  "Access-Control-Max-Age": "86400"
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    try {
      if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders });
      const url = new URL(request.url);
      const path = url.pathname.replace(/\/$/, "") || "/";

      if (request.method === "GET" && path === "/health") {
        await env.DB.prepare("SELECT 1").first();
        return json({ status: "ok", service: "zplanner-api" });
      }

      if (request.method === "POST" && path === "/auth/register") return await register(request, env);
      if (request.method === "POST" && path === "/auth/login") return await login(request, env);

      const session = await requireSession(request, env);
      if (request.method === "GET" && path === "/auth/me") {
        return json({ user: { id: session.userId, email: session.email } });
      }
      if (request.method === "POST" && path === "/auth/logout") {
        await env.DB.prepare("UPDATE sessions SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL")
          .bind(Math.floor(Date.now() / 1000), session.sessionId, session.userId).run();
        return new Response(null, { status: 204, headers: corsHeaders });
      }
      if (request.method === "GET" && path === "/tasks") return await listTasks(url, env, session.userId);
      if (request.method === "POST" && path === "/tasks") return await createTask(request, env, session.userId);

      const taskMatch = path.match(/^\/tasks\/(\d+)$/);
      if (taskMatch && request.method === "PUT") return await updateTask(request, env, session.userId, Number(taskMatch[1]));
      if (taskMatch && request.method === "DELETE") return await deleteTask(env, session.userId, Number(taskMatch[1]));

      throw new HttpError(404, "NOT_FOUND", "Không tìm thấy endpoint.");
    } catch (error) {
      if (error instanceof HttpError) return json({ error: { code: error.code, message: error.message } }, error.status);
      console.error("Unhandled request error", error);
      return json({ error: { code: "INTERNAL_ERROR", message: "Đã xảy ra lỗi máy chủ." } }, 500);
    }
  }
};

async function register(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  const password = typeof body.password === "string" ? body.password : "";
  if (!/^\S+@\S+\.\S+$/.test(email) || email.length > 254) {
    throw new HttpError(400, "INVALID_EMAIL", "Email không hợp lệ.");
  }
  if (password.length < 8 || password.length > 128) {
    throw new HttpError(400, "INVALID_PASSWORD", "Mật khẩu cần có từ 8 đến 128 ký tự.");
  }

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const hash = await derivePassword(password, salt, PASSWORD_ITERATIONS);
  const userId = crypto.randomUUID();
  const duplicate = await env.DB.prepare("SELECT id FROM users WHERE email = ?").bind(email).first<{ id: string }>();
  if (duplicate) throw new HttpError(409, "EMAIL_EXISTS", "Email này đã được đăng ký.");
  try {
    await env.DB.prepare(
      "INSERT INTO users (id, email, password_hash, password_salt, password_iterations) VALUES (?, ?, ?, ?, ?)"
    ).bind(userId, email, toBase64Url(hash), toBase64Url(salt), PASSWORD_ITERATIONS).run();
  } catch (error) {
    const duplicateAfterRace = await env.DB.prepare("SELECT id FROM users WHERE email = ?").bind(email).first<{ id: string }>();
    if (duplicateAfterRace) throw new HttpError(409, "EMAIL_EXISTS", "Email này đã được đăng ký.");
    throw error;
  }

  return createSessionResponse(env, userId, email, 201);
}

async function login(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  const password = typeof body.password === "string" ? body.password : "";
  const user = await env.DB.prepare(
    "SELECT id, email, password_hash, password_salt, password_iterations FROM users WHERE email = ?"
  ).bind(email).first<UserRow>();

  if (!user || password.length > 128) throw invalidCredentials();
  const expected = fromBase64Url(user.password_hash);
  const actual = await derivePassword(password, fromBase64Url(user.password_salt), user.password_iterations);
  if (!constantTimeEqual(expected, actual)) throw invalidCredentials();
  return createSessionResponse(env, user.id, user.email, 200);
}

function invalidCredentials(): HttpError {
  return new HttpError(401, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.");
}

async function createSessionResponse(env: Env, userId: string, email: string, status: number): Promise<Response> {
  const secret = requireSecret(env);
  const now = Math.floor(Date.now() / 1000);
  const sessionId = crypto.randomUUID();
  const expiresAt = now + TOKEN_TTL_SECONDS;
  await env.DB.prepare("INSERT INTO sessions (id, user_id, expires_at) VALUES (?, ?, ?)")
    .bind(sessionId, userId, expiresAt).run();

  const header = toBase64Url(encoder.encode(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const payload = toBase64Url(encoder.encode(JSON.stringify({
    sub: userId,
    email,
    jti: sessionId,
    iss: "zplanner",
    aud: "zplanner-mobile",
    iat: now,
    exp: expiresAt
  })));
  const unsignedToken = `${header}.${payload}`;
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(unsignedToken));
  const token = `${unsignedToken}.${toBase64Url(new Uint8Array(signature))}`;
  return json({ accessToken: token, tokenType: "Bearer", expiresAt, user: { id: userId, email } }, status);
}

interface Session {
  userId: string;
  email: string;
  sessionId: string;
}

async function requireSession(request: Request, env: Env): Promise<Session> {
  const authorization = request.headers.get("Authorization") ?? "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  const parts = token.split(".");
  if (parts.length !== 3) throw new HttpError(401, "UNAUTHORIZED", "Cần đăng nhập để tiếp tục.");

  let userId: string;
  let email: string;
  let sessionId: string;
  try {
    const secret = requireSecret(env);
    const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const validSignature = await crypto.subtle.verify("HMAC", key, toArrayBuffer(fromBase64Url(parts[2])), encoder.encode(`${parts[0]}.${parts[1]}`));
    if (!validSignature) throw new Error("invalid signature");
    const header = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[0]))) as { alg?: string };
    const payload = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1]))) as {
      sub?: string; email?: string; jti?: string; iss?: string; aud?: string; exp?: number;
    };
    if (header.alg !== "HS256" || payload.iss !== "zplanner" || payload.aud !== "zplanner-mobile" ||
        !payload.sub || !payload.email || !payload.jti || !payload.exp || payload.exp <= Math.floor(Date.now() / 1000)) {
      throw new Error("invalid claims");
    }
    userId = payload.sub;
    email = payload.email;
    sessionId = payload.jti;
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(401, "UNAUTHORIZED", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
  }
  const activeSession = await env.DB.prepare(
    "SELECT id FROM sessions WHERE id = ? AND user_id = ? AND expires_at > ? AND revoked_at IS NULL"
  ).bind(sessionId, userId, Math.floor(Date.now() / 1000)).first<{ id: string }>();
  if (!activeSession) throw new HttpError(401, "UNAUTHORIZED", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
  return { userId, email, sessionId };
}

async function listTasks(url: URL, env: Env, ownerId: string): Promise<Response> {
  const date = url.searchParams.get("date");
  const startDate = url.searchParams.get("startDate");
  const endDate = url.searchParams.get("endDate");
  const status = url.searchParams.get("status");
  if (date && !isValidDate(date)) throw new HttpError(400, "INVALID_DATE", "Ngày phải theo định dạng YYYY-MM-DD.");
  if (startDate && !isValidDate(startDate)) throw new HttpError(400, "INVALID_DATE", "startDate phải theo định dạng YYYY-MM-DD.");
  if (endDate && !isValidDate(endDate)) throw new HttpError(400, "INVALID_DATE", "endDate phải theo định dạng YYYY-MM-DD.");
  if ((startDate && !endDate) || (!startDate && endDate) || (startDate && endDate && startDate > endDate)) {
    throw new HttpError(400, "INVALID_DATE_RANGE", "Cần truyền khoảng ngày hợp lệ.");
  }
  if (status && !isStatus(status)) throw new HttpError(400, "INVALID_STATUS", "Trạng thái không hợp lệ.");

  let query = "SELECT id, title, description, date, start_time AS startTime, end_time AS endTime, priority, status, created_at AS createdAt, updated_at AS updatedAt FROM tasks WHERE owner_id = ?";
  const params: Array<string | number> = [ownerId];
  if (date) { query += " AND date = ?"; params.push(date); }
  if (startDate && endDate) { query += " AND date BETWEEN ? AND ?"; params.push(startDate, endDate); }
  if (status) { query += " AND status = ?"; params.push(status); }
  query += " ORDER BY date, start_time, id";
  const result = await env.DB.prepare(query).bind(...params).all();
  return json({ tasks: result.results });
}

async function createTask(request: Request, env: Env, ownerId: string): Promise<Response> {
  const task = validateTask(await readJson(request));
  const result = await env.DB.prepare(
    "INSERT INTO tasks (owner_id, title, description, date, start_time, end_time, priority, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
  ).bind(ownerId, task.title, task.description, task.date, task.startTime, task.endTime, task.priority, task.status).run();
  const created = await getTask(env, ownerId, Number(result.meta.last_row_id));
  return json({ task: created }, 201);
}

async function updateTask(request: Request, env: Env, ownerId: string, taskId: number): Promise<Response> {
  if (!Number.isSafeInteger(taskId) || taskId <= 0) throw new HttpError(400, "INVALID_TASK_ID", "Mã công việc không hợp lệ.");
  const task = validateTask(await readJson(request));
  const existing = await getTask(env, ownerId, taskId);
  if (!existing) throw new HttpError(404, "TASK_NOT_FOUND", "Không tìm thấy công việc.");
  await env.DB.prepare(
    "UPDATE tasks SET title = ?, description = ?, date = ?, start_time = ?, end_time = ?, priority = ?, status = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ? AND owner_id = ?"
  ).bind(task.title, task.description, task.date, task.startTime, task.endTime, task.priority, task.status, taskId, ownerId).run();
  return json({ task: await getTask(env, ownerId, taskId) });
}

async function deleteTask(env: Env, ownerId: string, taskId: number): Promise<Response> {
  if (!Number.isSafeInteger(taskId) || taskId <= 0) throw new HttpError(400, "INVALID_TASK_ID", "Mã công việc không hợp lệ.");
  const result = await env.DB.prepare("DELETE FROM tasks WHERE id = ? AND owner_id = ?").bind(taskId, ownerId).run();
  if (!result.meta.changes) throw new HttpError(404, "TASK_NOT_FOUND", "Không tìm thấy công việc.");
  return new Response(null, { status: 204, headers: corsHeaders });
}

async function getTask(env: Env, ownerId: string, taskId: number): Promise<Record<string, unknown> | null> {
  return env.DB.prepare(
    "SELECT id, title, description, date, start_time AS startTime, end_time AS endTime, priority, status, created_at AS createdAt, updated_at AS updatedAt FROM tasks WHERE id = ? AND owner_id = ?"
  ).bind(taskId, ownerId).first<Record<string, unknown>>();
}

function validateTask(body: Record<string, unknown>): TaskInput {
  const title = typeof body.title === "string" ? body.title.trim() : "";
  const description = typeof body.description === "string" ? body.description.trim() : "";
  const date = typeof body.date === "string" ? body.date : "";
  const startTime = typeof body.startTime === "string" ? body.startTime : "";
  const endTime = typeof body.endTime === "string" ? body.endTime : "";
  const priority = body.priority === undefined ? "medium" : body.priority;
  const status = body.status === undefined ? "todo" : body.status;
  if (!title || title.length > 120) throw new HttpError(400, "INVALID_TITLE", "Tiêu đề cần có từ 1 đến 120 ký tự.");
  if (description.length > 2000) throw new HttpError(400, "INVALID_DESCRIPTION", "Mô tả tối đa 2000 ký tự.");
  if (!isValidDate(date)) throw new HttpError(400, "INVALID_DATE", "Ngày phải là ngày hợp lệ theo định dạng YYYY-MM-DD.");
  if (!isValidTime(startTime) || !isValidTime(endTime) || endTime <= startTime) {
    throw new HttpError(400, "INVALID_TIME", "Giờ phải theo định dạng HH:mm và giờ kết thúc phải sau giờ bắt đầu.");
  }
  if (!isPriority(priority)) throw new HttpError(400, "INVALID_PRIORITY", "Mức ưu tiên không hợp lệ.");
  if (!isStatus(status)) throw new HttpError(400, "INVALID_STATUS", "Trạng thái không hợp lệ.");
  return { title, description, date, startTime, endTime, priority, status };
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const length = Number(request.headers.get("Content-Length") ?? 0);
  if (length > 64 * 1024) throw new HttpError(413, "BODY_TOO_LARGE", "Dữ liệu gửi lên quá lớn.");
  try {
    const value: unknown = await request.json();
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Expected object");
    return value as Record<string, unknown>;
  } catch {
    throw new HttpError(400, "INVALID_JSON", "Nội dung JSON không hợp lệ.");
  }
}

function isValidDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function isValidTime(value: string): boolean {
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) return false;
  return true;
}

function isPriority(value: unknown): value is TaskInput["priority"] {
  return value === "low" || value === "medium" || value === "high";
}

function isStatus(value: unknown): value is TaskInput["status"] {
  return value === "todo" || value === "in_progress" || value === "done";
}

async function derivePassword(password: string, salt: Uint8Array, iterations: number): Promise<Uint8Array> {
  const baseKey = await crypto.subtle.importKey("raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  const derived = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: toArrayBuffer(salt), iterations }, baseKey, 256);
  return new Uint8Array(derived);
}

function requireSecret(env: Env): string {
  if (!env.AUTH_SECRET || env.AUTH_SECRET.length < 32) {
    throw new HttpError(500, "SERVER_NOT_CONFIGURED", "Máy chủ chưa được cấu hình xác thực.");
  }
  return env.AUTH_SECRET;
}

function toBase64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

function fromBase64Url(value: string): Uint8Array {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(base64 + "=".repeat((4 - (base64.length % 4)) % 4));
  return Uint8Array.from(binary, character => character.charCodeAt(0));
}

function toArrayBuffer(value: Uint8Array): ArrayBuffer {
  return value.slice().buffer as ArrayBuffer;
}

function constantTimeEqual(left: Uint8Array, right: Uint8Array): boolean {
  let difference = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i++) difference |= (left[i] ?? 0) ^ (right[i] ?? 0);
  return difference === 0;
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
  });
}
