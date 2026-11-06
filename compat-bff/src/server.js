import crypto from "node:crypto";
import express from "express";
import session from "express-session";
import connectPgSimple from "connect-pg-simple";
import pg from "pg";
import helmet from "helmet";
import { rateLimit } from "express-rate-limit";
import { csrfToken, matchLegacyRoute, toCamel, toSnake } from "./legacy-routes.js";

const required = ["DATABASE_URL", "SESSION_SECRET", "KOTLIN_API_URL"];
for (const key of required) {
  if (!process.env[key]) throw new Error("Missing required environment variable: " + key);
}
if (process.env.SESSION_SECRET.length < 32) throw new Error("SESSION_SECRET must contain at least 32 characters");

const production = process.env.NODE_ENV === "production";
const apiBase = process.env.KOTLIN_API_URL.replace(/\/$/, "");
const pool = new pg.Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: process.env.PGSSL === "true" ? { rejectUnauthorized: true } : undefined,
  max: 10,
});
const PgStore = connectPgSimple(session);
const app = express();
app.disable("x-powered-by");
app.set("trust proxy", process.env.TRUST_PROXY === "true" ? 1 : false);
app.use(helmet({ contentSecurityPolicy: false, crossOriginEmbedderPolicy: false }));
app.use(express.json({ limit: "256kb" }));
app.use(express.raw({ type: "multipart/form-data", limit: "20mb" }));
app.use(session({
  name: production ? "__Host-imobpro.sid" : "imobpro.sid",
  store: new PgStore({ pool, tableName: "compat_sessions", createTableIfMissing: true, pruneSessionInterval: 900 }),
  secret: process.env.SESSION_SECRET,
  resave: false,
  saveUninitialized: false,
  rolling: true,
  cookie: {
    httpOnly: true,
    secure: production,
    sameSite: "lax",
    path: "/",
    maxAge: 7 * 24 * 60 * 60 * 1000,
  },
}));

const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 20, standardHeaders: "draft-7", legacyHeaders: false });
const mutationMethods = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function fail(res, status, code, message) {
  return res.status(status).json({ error: { code, message } });
}
function requireCsrf(req, res, next) {
  if (!mutationMethods.has(req.method)) return next();
  const cookie = req.cookies?.["imobpro.csrf"] || req.get("x-csrf-cookie");
  const header = req.get("x-csrf-token");
  if (!cookie || !header || cookie.length !== header.length || !crypto.timingSafeEqual(Buffer.from(cookie), Buffer.from(header))) {
    return fail(res, 403, "CSRF_INVALID", "Atualize o token CSRF e tente novamente.");
  }
  next();
}
// Parse the readable CSRF cookie without adding a cookie-parser dependency.
app.use((req, _res, next) => {
  const raw = req.headers.cookie || "";
  req.cookies = Object.fromEntries(raw.split(";").map(part => part.trim()).filter(Boolean).map(part => {
    const at = part.indexOf("=");
    return at < 0 ? [part, ""] : [decodeURIComponent(part.slice(0, at)), decodeURIComponent(part.slice(at + 1))];
  }));
  next();
});

app.get("/api/csrf", (req, res) => {
  const token = csrfToken();
  res.cookie("imobpro.csrf", token, { httpOnly: false, secure: production, sameSite: "lax", path: "/" });
  res.set("Cache-Control", "no-store");
  res.json({ csrfToken: token });
});

async function apiCall(path, { method = "GET", body, token, headers = {} } = {}) {
  const response = await fetch(apiBase + path, {
    method,
    headers: {
      ...(body !== undefined && !(body instanceof Buffer) ? { "content-type": "application/json" } : {}),
      ...(token ? { authorization: "Bearer " + token } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : body instanceof Buffer ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : null; } catch { data = raw; }
  return { status: response.status, data, headers: response.headers };
}

async function refreshSession(req) {
  if (!req.session.refreshToken) return false;
  const result = await apiCall("/api/v1/auth/refresh", {
    method: "POST",
    body: { refreshToken: req.session.refreshToken },
  });
  if (result.status !== 200) {
    req.session.destroy(() => {});
    return false;
  }
  req.session.accessToken = result.data.accessToken;
  req.session.refreshToken = result.data.refreshToken;
  req.session.accessExpiresAt = result.data.expiresAt;
  return true;
}

async function authenticatedCall(req, path, options = {}) {
  if (!req.session.accessToken) return { status: 401, data: { message: "Authentication required" } };
  let result = await apiCall(path, { ...options, token: req.session.accessToken });
  if (result.status === 401 && await refreshSession(req)) {
    result = await apiCall(path, { ...options, token: req.session.accessToken });
  }
  return result;
}

function sendApiResult(res, result) {
  res.status(result.status);
  if (result.headers?.get("content-type")) res.set("content-type", result.headers.get("content-type"));
  if (result.data === null || result.data === undefined) return res.end();
  if (typeof result.data === "string") return res.send(result.data);
  return res.json(toSnake(result.data));
}

app.post("/api/auth/organizations", authLimiter, requireCsrf, async (req, res) => {
  try {
    const result = await apiCall("/api/v1/auth/organizations", {
      method: "POST",
      body: toCamel(req.body || {}),
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "API Kotlin indisponível."); }
});

app.post("/api/auth/login", authLimiter, requireCsrf, async (req, res) => {
  try {
    const credentials = toCamel(req.body || {});
    const result = await apiCall("/api/v1/auth/login", { method: "POST", body: credentials });
    if (result.status !== 200) return sendApiResult(res, result);
    await new Promise((resolve, reject) => req.session.regenerate(error => error ? reject(error) : resolve()));
    req.session.accessToken = result.data.accessToken;
    req.session.refreshToken = result.data.refreshToken;
    req.session.accessExpiresAt = result.data.expiresAt;
    const me = await authenticatedCall(req, "/api/v1/auth/me");
    return res.status(200).json(toSnake(me.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível concluir o login."); }
});

app.post("/api/auth/register", authLimiter, requireCsrf, async (_req, res) => {
  return fail(res, 409, "REGISTRATION_FLOW_REQUIRED",
    "O cadastro legado criava clientes sem organização. Use o fluxo de cadastro de organização/convite; não criaremos um proprietário por engano.");
});

app.get("/api/auth/me", async (req, res) => {
  if (!req.session.accessToken) return res.json(null);
  try {
    const result = await authenticatedCall(req, "/api/v1/auth/me");
    if (result.status === 401) return res.json(null);
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "API Kotlin indisponível."); }
});

app.post("/api/auth/logout", requireCsrf, async (req, res) => {
  try {
    if (req.session.accessToken) {
      await authenticatedCall(req, "/api/v1/auth/logout", { method: "POST" });
    }
    await new Promise(resolve => req.session.destroy(() => resolve()));
    res.clearCookie(production ? "__Host-imobpro.sid" : "imobpro.sid", { path: "/" });
    return res.json({ ok: true });
  } catch { return fail(res, 502, "LOGOUT_FAILED", "Não foi possível encerrar a sessão."); }
});

app.use("/api", async (req, res, next) => {
  if (req.path === "/csrf" || req.path.startsWith("/auth/")) return next();
  if (mutationMethods.has(req.method)) {
    const cookie = req.cookies?.["imobpro.csrf"] || req.get("x-csrf-cookie");
    const header = req.get("x-csrf-token");
    if (!cookie || !header || cookie.length !== header.length ||
        !crypto.timingSafeEqual(Buffer.from(cookie), Buffer.from(header))) {
      return fail(res, 403, "CSRF_INVALID", "Token CSRF inválido.");
    }
  }
  const legacyPath = req.baseUrl + req.path;
  const route = matchLegacyRoute(req.method, legacyPath);
  if (!route) return fail(res, 501, "LEGACY_ROUTE_NOT_MAPPED",
    "Este endpoint legado ainda não possui adaptador validado. Nenhuma chamada foi encaminhada.");
  try {
    if (route.target === "/api/v1/health") return sendApiResult(res, await apiCall(route.target));
    const target = route.target + (req.url.includes("?") ? req.url.slice(req.url.indexOf("?")) : "");
    const payload = req.body instanceof Buffer ? req.body : req.body && Object.keys(req.body).length ? toCamel(req.body) : undefined;
    const result = await authenticatedCall(req, target, { method: req.method, body: payload,
      headers: req.body instanceof Buffer ? { "content-type": req.get("content-type") } : {} });
    return sendApiResult(res, result);
  } catch {
    return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Falha ao comunicar com a API Kotlin.");
  }
});

app.get("/healthz", async (_req, res) => {
  try {
    await pool.query("SELECT 1");
    res.json({ status: "ok" });
  } catch { res.status(503).json({ status: "unavailable" }); }
});

app.use((error, _req, res, _next) => {
  if (error?.type === "entity.too.large") return fail(res, 413, "PAYLOAD_TOO_LARGE", "Corpo excede o limite permitido.");
  return fail(res, 500, "INTERNAL_ERROR", "Erro interno.");
});

const port = Number(process.env.PORT || 3100);
app.listen(port, "0.0.0.0", () => process.stdout.write("Imob-Pro compatibility BFF listening on " + port + "\n"));
