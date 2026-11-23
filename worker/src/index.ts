const PASSWORD_ITERATIONS = 100_000;
const TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;
const encoder = new TextEncoder();

interface Env {
  DB: D1Database;
  AUTH_SECRET: string;
  BREVO_API_KEY?: string;
  RESET_EMAIL_FROM?: string;
  BREVO_API_URL?: string;
  GOOGLE_WEB_CLIENT_ID: string;
  GOOGLE_JWKS_URL?: string;
}

interface UserRow {
  id: string;
  email: string;
  auth_provider?: string;
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

interface EventInput {
  title: string;
  description: string;
  location: string;
  startAt: string;
  endAt: string;
  reminderMinutes: number;
}

class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: Record<string, unknown>
  ) {
    super(message);
  }
}

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
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
      if (request.method === "POST" && path === "/auth/password-reset/request") return await requestPasswordReset(request, env);
      if (request.method === "POST" && path === "/auth/password-reset/confirm") return await confirmPasswordReset(request, env);
      if (request.method === "POST" && path === "/auth/google") return await googleAuth(request, env);

      const session = await requireSession(request, env);
      if (request.method === "POST" && path === "/auth/google/link") return await linkGoogle(request, env, session);
      if (request.method === "GET" && path === "/profile") return await getProfile(env, session.userId);
      if (request.method === "PATCH" && path === "/profile") return await updateProfile(request, env, session.userId);
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
      if (request.method === "GET" && path === "/events") return await listEvents(url, env, session.userId);
      if (request.method === "POST" && path === "/events") return await createEvent(request, env, session.userId);

      const eventMatch = path.match(/^\/events\/(\d+)$/);
      if (eventMatch && request.method === "PUT") return await updateEvent(request, env, session.userId, Number(eventMatch[1]));
      if (eventMatch && request.method === "DELETE") return await deleteEvent(env, session.userId, Number(eventMatch[1]));

      const taskMatch = path.match(/^\/tasks\/(\d+)$/);
      if (taskMatch && request.method === "PUT") return await updateTask(request, env, session.userId, Number(taskMatch[1]));
      if (taskMatch && request.method === "DELETE") return await deleteTask(env, session.userId, Number(taskMatch[1]));

      throw new HttpError(404, "NOT_FOUND", "Không tìm thấy endpoint.");
    } catch (error) {
      if (error instanceof HttpError) return json({ error: { code: error.code, message: error.message, ...(error.details ?? {}) } }, error.status);
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
    "SELECT id, email, password_hash, password_salt, password_iterations FROM users WHERE email = ? AND auth_provider = 'password'"
  ).bind(email).first<UserRow>();

  if (!user || password.length > 128 || !user.password_hash || !user.password_salt) throw invalidCredentials();
  const expected = fromBase64Url(user.password_hash);
  const actual = await derivePassword(password, fromBase64Url(user.password_salt), user.password_iterations);
  if (!constantTimeEqual(expected, actual)) throw invalidCredentials();
  return createSessionResponse(env, user.id, user.email, 200);
}

function invalidCredentials(): HttpError {
  return new HttpError(401, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.");
}

async function getProfile(env: Env, userId: string): Promise<Response> {
  const row = await env.DB.prepare("SELECT display_name, avatar_base64 FROM user_profiles WHERE user_id = ?")
    .bind(userId).first<{ display_name: string | null; avatar_base64: string | null }>();
  return json({ profile: { displayName: row?.display_name ?? null, avatarBase64: row?.avatar_base64 ?? null } });
}

async function updateProfile(request: Request, env: Env, userId: string): Promise<Response> {
  const body = await readJson(request);
  const hasName = Object.hasOwn(body, "displayName");
  const hasAvatar = Object.hasOwn(body, "avatarBase64");
  if (!hasName && !hasAvatar) throw new HttpError(400, "INVALID_PROFILE", "Không có thông tin hồ sơ để lưu.");
  let name: string | null = null;
  let avatar: string | null = null;
  if (hasName) {
    if (typeof body.displayName !== "string") throw new HttpError(400, "INVALID_NAME", "Tên không hợp lệ.");
    name = body.displayName.trim() || null;
    if (name && name.length > 80) throw new HttpError(400, "INVALID_NAME", "Tên tối đa 80 ký tự.");
  }
  if (hasAvatar) {
    if (typeof body.avatarBase64 !== "string" || body.avatarBase64.length > 52_000 ||
        !/^[A-Za-z0-9+/]+={0,2}$/.test(body.avatarBase64)) {
      throw new HttpError(400, "INVALID_AVATAR", "Ảnh đại diện không hợp lệ hoặc quá lớn.");
    }
    try {
      const bytes = atob(body.avatarBase64);
      if (bytes.length > 39_000 || bytes.length < 4 || bytes.charCodeAt(0) !== 0xff ||
          bytes.charCodeAt(1) !== 0xd8 || bytes.charCodeAt(2) !== 0xff) throw new Error("Invalid JPEG");
      avatar = body.avatarBase64;
    } catch {
      throw new HttpError(400, "INVALID_AVATAR", "Ảnh đại diện phải là JPEG hợp lệ, tối đa 39 KB.");
    }
  }
  await env.DB.prepare(`INSERT INTO user_profiles (user_id, display_name, avatar_base64) VALUES (?, ?, ?)
    ON CONFLICT(user_id) DO UPDATE SET
      display_name = CASE WHEN ? THEN excluded.display_name ELSE user_profiles.display_name END,
      avatar_base64 = CASE WHEN ? THEN excluded.avatar_base64 ELSE user_profiles.avatar_base64 END`)
    .bind(userId, name, avatar, hasName ? 1 : 0, hasAvatar ? 1 : 0).run();
  return getProfile(env, userId);
}

async function resetCodeHash(env: Env, userId: string, code: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(requireSecret(env)), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return toBase64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(`${userId}:${code}`))));
}

async function requestPasswordReset(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  if (!/^\S+@\S+\.\S+$/.test(email) || email.length > 254) throw new HttpError(400, "INVALID_EMAIL", "Email không hợp lệ.");
  requireSecret(env);
  if (!env.BREVO_API_KEY || !env.RESET_EMAIL_FROM) throw new HttpError(503, "EMAIL_NOT_CONFIGURED", "Dịch vụ gửi email chưa được cấu hình.");
  const accepted = json({ message: "Nếu email thuộc tài khoản mật khẩu, mã đặt lại sẽ được gửi." }, 202);
  const user = await env.DB.prepare("SELECT id FROM users WHERE email = ? AND auth_provider = 'password'").bind(email).first<{ id: string }>();
  if (!user) return accepted;
  const now = Math.floor(Date.now() / 1000);
  const previous = await env.DB.prepare("SELECT requested_at FROM password_reset_codes WHERE user_id = ?").bind(user.id).first<{ requested_at: number }>();
  if (previous && previous.requested_at > now - 60) return accepted;
  const limit = Math.floor(2 ** 32 / 100_000_000) * 100_000_000;
  let random: number;
  do { random = crypto.getRandomValues(new Uint32Array(1))[0]; } while (random >= limit);
  const code = String(random % 100_000_000).padStart(8, "0");
  const hash = await resetCodeHash(env, user.id, code);
  await env.DB.prepare("INSERT INTO password_reset_codes (user_id, code_hash, expires_at, attempts, requested_at) VALUES (?, ?, ?, 0, ?) ON CONFLICT(user_id) DO UPDATE SET code_hash = excluded.code_hash, expires_at = excluded.expires_at, attempts = 0, requested_at = excluded.requested_at")
    .bind(user.id, hash, now + 600, now).run();
  try {
    const sent = await fetch(env.BREVO_API_URL || "https://api.brevo.com/v3/smtp/email", {
      method: "POST",
      headers: { "api-key": env.BREVO_API_KEY, "Content-Type": "application/json", "accept": "application/json" },
      body: JSON.stringify({ sender: { name: "ZPlanner", email: env.RESET_EMAIL_FROM }, to: [{ email }], subject: "Mã đặt lại mật khẩu ZPlanner", textContent: `Mã đặt lại mật khẩu ZPlanner của bạn là ${code}. Mã hết hạn sau 10 phút. Nếu bạn không yêu cầu, hãy bỏ qua email này.` })
    });
    if (!sent.ok) throw new Error(`Email provider returned ${sent.status}`);
  } catch (error) {
    await env.DB.prepare("DELETE FROM password_reset_codes WHERE user_id = ? AND code_hash = ?").bind(user.id, hash).run();
    console.error("Password reset email delivery failed", error);
    throw new HttpError(503, "EMAIL_UNAVAILABLE", "Không thể gửi mã lúc này. Vui lòng thử lại sau.");
  }
  return accepted;
}

async function confirmPasswordReset(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  const code = typeof body.code === "string" ? body.code : "";
  const password = typeof body.newPassword === "string" ? body.newPassword : "";
  if (password.length < 8 || password.length > 128) throw new HttpError(400, "INVALID_PASSWORD", "Mật khẩu cần có từ 8 đến 128 ký tự.");
  if (!/^\d{8}$/.test(code)) throw new HttpError(400, "INVALID_RESET_CODE", "Mã đặt lại không hợp lệ hoặc đã hết hạn.");
  const user = await env.DB.prepare("SELECT id FROM users WHERE email = ? AND auth_provider = 'password'").bind(email).first<{ id: string }>();
  if (!user) throw new HttpError(400, "INVALID_RESET_CODE", "Mã đặt lại không hợp lệ hoặc đã hết hạn.");
  const now = Math.floor(Date.now() / 1000);
  const row = await env.DB.prepare("SELECT code_hash, expires_at, attempts FROM password_reset_codes WHERE user_id = ?").bind(user.id).first<{ code_hash: string; expires_at: number; attempts: number }>();
  if (!row || row.expires_at <= now || row.attempts >= 5) throw new HttpError(400, "INVALID_RESET_CODE", "Mã đặt lại không hợp lệ hoặc đã hết hạn.");
  const hash = await resetCodeHash(env, user.id, code);
  if (!constantTimeEqual(fromBase64Url(row.code_hash), fromBase64Url(hash))) {
    await env.DB.prepare("UPDATE password_reset_codes SET attempts = attempts + 1 WHERE user_id = ? AND attempts < 5").bind(user.id).run();
    throw new HttpError(400, "INVALID_RESET_CODE", "Mã đặt lại không hợp lệ hoặc đã hết hạn.");
  }
  const claimed = await env.DB.prepare("DELETE FROM password_reset_codes WHERE user_id = ? AND code_hash = ? AND expires_at > ? AND attempts < 5").bind(user.id, hash, now).run();
  if (!claimed.meta.changes) throw new HttpError(400, "INVALID_RESET_CODE", "Mã đặt lại không hợp lệ hoặc đã hết hạn.");
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const passwordHash = await derivePassword(password, salt, PASSWORD_ITERATIONS);
  await env.DB.batch([
    env.DB.prepare("UPDATE users SET password_hash = ?, password_salt = ?, password_iterations = ? WHERE id = ?").bind(toBase64Url(passwordHash), toBase64Url(salt), PASSWORD_ITERATIONS, user.id),
    env.DB.prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL").bind(now, user.id)
  ]);
  return json({ message: "Đã đổi mật khẩu. Vui lòng đăng nhập lại." });
}

interface GoogleClaims { iss?: string; aud?: string | string[]; sub?: string; email?: string; email_verified?: boolean; exp?: number; nonce?: string; }
interface GoogleJwk { kid?: string; kty?: string; alg?: string; use?: string; n?: string; e?: string; }

async function googleAuth(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const claims = await verifyGoogleIdToken(body.idToken, body.nonce, env);
  const linked = await env.DB.prepare("SELECT user_id FROM google_identities WHERE google_sub = ?").bind(claims.sub!).first<{ user_id: string }>();
  if (linked) return createSessionResponse(env, linked.user_id, claims.email!, 200);
  const existing = await env.DB.prepare("SELECT id, auth_provider FROM users WHERE email = ?").bind(claims.email!).first<{ id: string; auth_provider: string }>();
  if (existing) throw new HttpError(409, "ACCOUNT_LINK_REQUIRED", "Tài khoản email đã tồn tại. Hãy đăng nhập bằng mật khẩu rồi liên kết Google.");
  const userId = crypto.randomUUID();
  const unusablePasswordHash = crypto.getRandomValues(new Uint8Array(32));
  const unusablePasswordSalt = crypto.getRandomValues(new Uint8Array(16));
  await env.DB.batch([
    env.DB.prepare("INSERT INTO users (id, email, password_hash, password_salt, password_iterations, auth_provider) VALUES (?, ?, ?, ?, ?, 'google')")
      .bind(userId, claims.email!, toBase64Url(unusablePasswordHash), toBase64Url(unusablePasswordSalt), PASSWORD_ITERATIONS),
    env.DB.prepare("INSERT INTO google_identities (google_sub, user_id, email) VALUES (?, ?, ?)").bind(claims.sub!, userId, claims.email!)
  ]);
  return createSessionResponse(env, userId, claims.email!, 201);
}

async function linkGoogle(request: Request, env: Env, session: Session): Promise<Response> {
  const linkBody = await readJson(request);
  const claims = await verifyGoogleIdToken(linkBody.idToken, linkBody.nonce, env);
  const linked = await env.DB.prepare("SELECT user_id FROM google_identities WHERE google_sub = ?").bind(claims.sub!).first<{ user_id: string }>();
  if (linked && linked.user_id !== session.userId) throw new HttpError(409, "GOOGLE_IDENTITY_ALREADY_LINKED", "Tài khoản Google đã được liên kết với tài khoản khác.");
  if (!linked) {
    const account = await env.DB.prepare("SELECT email FROM users WHERE id = ? AND auth_provider = 'password'").bind(session.userId).first<{ email: string }>();
    if (!account || account.email.toLowerCase() !== claims.email!.toLowerCase()) {
      throw new HttpError(409, "GOOGLE_EMAIL_MISMATCH", "Email Google phải khớp với tài khoản đang đăng nhập.");
    }
    try {
      await env.DB.prepare("INSERT INTO google_identities (google_sub, user_id, email) VALUES (?, ?, ?)").bind(claims.sub!, session.userId, claims.email!).run();
    } catch {
      throw new HttpError(409, "GOOGLE_IDENTITY_ALREADY_LINKED", "Tài khoản Google đã được liên kết với tài khoản khác.");
    }
  }
  return createSessionResponse(env, session.userId, session.email, 200);
}

async function verifyGoogleIdToken(raw: unknown, expectedNonce: unknown, env: Env): Promise<Required<Pick<GoogleClaims, "sub" | "email" | "exp">>> {
  if (typeof raw !== "string" || raw.length > 8192) throw new HttpError(401, "INVALID_GOOGLE_TOKEN", "Google token không hợp lệ.");
  const parts = raw.split(".");
  if (parts.length !== 3) throw new HttpError(401, "INVALID_GOOGLE_TOKEN", "Google token không hợp lệ.");
  try {
    const header = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[0]))) as { kid?: string; alg?: string };
    const claims = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1]))) as GoogleClaims;
    const audienceOk = Array.isArray(claims.aud) ? claims.aud.includes(env.GOOGLE_WEB_CLIENT_ID) : claims.aud === env.GOOGLE_WEB_CLIENT_ID;
    if (typeof expectedNonce !== "string" || !expectedNonce || claims.nonce !== expectedNonce || header.alg !== "RS256" || (claims.iss !== "https://accounts.google.com" && claims.iss !== "accounts.google.com") || !audienceOk || !claims.sub || !claims.email || claims.email_verified !== true || !claims.exp || claims.exp <= Math.floor(Date.now() / 1000)) throw new HttpError(401, "INVALID_GOOGLE_TOKEN", "Google token không hợp lệ.");
    const jwksResponse = await fetch(env.GOOGLE_JWKS_URL || "https://www.googleapis.com/oauth2/v3/certs");
    if (!jwksResponse.ok) throw new Error("jwks");
    const jwks = await jwksResponse.json() as { keys?: GoogleJwk[] };
    const jwk = jwks.keys?.find(key => key.kid === header.kid && key.kty === "RSA" && key.alg === "RS256");
    if (!jwk?.n || !jwk.e) throw new Error("key");
    const key = await crypto.subtle.importKey("jwk", jwk as JsonWebKey, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    if (!await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, toArrayBuffer(fromBase64Url(parts[2])), encoder.encode(`${parts[0]}.${parts[1]}`))) throw new Error("signature");
    return { sub: claims.sub, email: claims.email.trim().toLowerCase(), exp: claims.exp };
  } catch (error) {
    if (error instanceof HttpError) throw error;
    throw new HttpError(401, "INVALID_GOOGLE_TOKEN", "Google token không hợp lệ.");
  }
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

async function listEvents(url: URL, env: Env, ownerId: string): Promise<Response> {
  const from = url.searchParams.get("from");
  const to = url.searchParams.get("to");
  if ((from && !to) || (!from && to) || (from && (!isValidTimestamp(from) || !isValidTimestamp(to!) || from >= to!))) {
    throw new HttpError(400, "INVALID_EVENT_RANGE", "from và to phải là khoảng ISO UTC hợp lệ.");
  }
  let query = "SELECT id, title, description, location, start_at AS startAt, end_at AS endAt, reminder_minutes AS reminderMinutes, created_at AS createdAt, updated_at AS updatedAt FROM events WHERE owner_id = ?";
  const params: Array<string | number> = [ownerId];
  if (from && to) {
    query += " AND start_at < ? AND ? < end_at";
    params.push(to, from);
  }
  query += " ORDER BY start_at, id";
  const result = await env.DB.prepare(query).bind(...params).all();
  return json({ events: result.results });
}

async function createEvent(request: Request, env: Env, ownerId: string): Promise<Response> {
  const body = await readJson(request);
  const event = validateEvent(body);
  const overlaps = await findEventOverlaps(env, ownerId, event.startAt, event.endAt);
  if (overlaps.length > 0 && body.confirmOverlap !== true) {
    throw new HttpError(409, "EVENT_OVERLAP", "Sự kiện bị trùng với lịch hiện có. Xác nhận để tiếp tục lưu.", { overlaps });
  }
  const result = await env.DB.prepare(
    "INSERT INTO events (owner_id, title, description, location, start_at, end_at, reminder_minutes) VALUES (?, ?, ?, ?, ?, ?, ?)"
  ).bind(ownerId, event.title, event.description, event.location, event.startAt, event.endAt, event.reminderMinutes).run();
  const eventId = Number(result.meta.last_row_id);
  if (!Number.isSafeInteger(eventId) || eventId <= 0) throw new HttpError(500, "EVENT_CREATE_FAILED", "Không thể xác định sự kiện vừa tạo.");
  return json({ event: await getEvent(env, ownerId, eventId), warnings: overlaps.length > 0 ? [{ code: "EVENT_OVERLAP", overlaps }] : [] }, 201);
}

async function updateEvent(request: Request, env: Env, ownerId: string, eventId: number): Promise<Response> {
  if (!Number.isSafeInteger(eventId) || eventId <= 0) throw new HttpError(400, "INVALID_EVENT_ID", "Mã sự kiện không hợp lệ.");
  const body = await readJson(request);
  const event = validateEvent(body);
  const existing = await getEvent(env, ownerId, eventId);
  if (!existing) throw new HttpError(404, "EVENT_NOT_FOUND", "Không tìm thấy sự kiện.");
  const overlaps = await findEventOverlaps(env, ownerId, event.startAt, event.endAt, eventId);
  if (overlaps.length > 0 && body.confirmOverlap !== true) {
    throw new HttpError(409, "EVENT_OVERLAP", "Sự kiện bị trùng với lịch hiện có. Xác nhận để tiếp tục lưu.", { overlaps });
  }
  await env.DB.prepare(
    "UPDATE events SET title = ?, description = ?, location = ?, start_at = ?, end_at = ?, reminder_minutes = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now') WHERE id = ? AND owner_id = ?"
  ).bind(event.title, event.description, event.location, event.startAt, event.endAt, event.reminderMinutes, eventId, ownerId).run();
  return json({ event: await getEvent(env, ownerId, eventId), warnings: overlaps.length > 0 ? [{ code: "EVENT_OVERLAP", overlaps }] : [] });
}

async function deleteEvent(env: Env, ownerId: string, eventId: number): Promise<Response> {
  if (!Number.isSafeInteger(eventId) || eventId <= 0) throw new HttpError(400, "INVALID_EVENT_ID", "Mã sự kiện không hợp lệ.");
  const result = await env.DB.prepare("DELETE FROM events WHERE id = ? AND owner_id = ?").bind(eventId, ownerId).run();
  if (!result.meta.changes) throw new HttpError(404, "EVENT_NOT_FOUND", "Không tìm thấy sự kiện.");
  return new Response(null, { status: 204, headers: corsHeaders });
}

async function getEvent(env: Env, ownerId: string, eventId: number): Promise<Record<string, unknown> | null> {
  return env.DB.prepare(
    "SELECT id, title, description, location, start_at AS startAt, end_at AS endAt, reminder_minutes AS reminderMinutes, created_at AS createdAt, updated_at AS updatedAt FROM events WHERE id = ? AND owner_id = ?"
  ).bind(eventId, ownerId).first<Record<string, unknown>>();
}

async function findEventOverlaps(env: Env, ownerId: string, startAt: string, endAt: string, excludeId?: number): Promise<Record<string, unknown>[]> {
  let query = "SELECT id, title, location, start_at AS startAt, end_at AS endAt FROM events WHERE owner_id = ? AND start_at < ? AND ? < end_at";
  const params: Array<string | number> = [ownerId, endAt, startAt];
  if (excludeId !== undefined) { query += " AND id <> ?"; params.push(excludeId); }
  query += " ORDER BY start_at, id";
  const result = await env.DB.prepare(query).bind(...params).all<Record<string, unknown>>();
  return result.results;
}

function validateEvent(body: Record<string, unknown>): EventInput {
  const title = typeof body.title === "string" ? body.title.trim() : "";
  const description = typeof body.description === "string" ? body.description.trim() : "";
  const location = typeof body.location === "string" ? body.location.trim() : "";
  const startAt = typeof body.startAt === "string" ? body.startAt : "";
  const endAt = typeof body.endAt === "string" ? body.endAt : "";
  const reminderMinutes = body.reminderMinutes === undefined ? 0 : body.reminderMinutes;
  if (!title || title.length > 120) throw new HttpError(400, "INVALID_TITLE", "Tiêu đề cần có từ 1 đến 120 ký tự.");
  if (description.length > 2000) throw new HttpError(400, "INVALID_DESCRIPTION", "Mô tả tối đa 2000 ký tự.");
  if (location.length > 500) throw new HttpError(400, "INVALID_LOCATION", "Địa điểm tối đa 500 ký tự.");
  if (!isValidTimestamp(startAt) || !isValidTimestamp(endAt) || startAt >= endAt) {
    throw new HttpError(400, "INVALID_INTERVAL", "startAt phải theo định dạng ISO và phải sớm hơn endAt.");
  }
  if (typeof reminderMinutes !== "number" || !Number.isSafeInteger(reminderMinutes) || reminderMinutes < 0 || reminderMinutes > 10080) {
    throw new HttpError(400, "INVALID_REMINDER", "reminderMinutes phải là số nguyên từ 0 đến 10080.");
  }
  return { title, description, location, startAt, endAt, reminderMinutes };
}

function isValidTimestamp(value: string): boolean {
  return value.length > 0 && Number.isFinite(Date.parse(value)) && value === new Date(value).toISOString();
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
