import crypto from "node:crypto";
import express from "express";
import session from "express-session";
import connectPgSimple from "connect-pg-simple";
import pg from "pg";
import helmet from "helmet";
import { rateLimit } from "express-rate-limit";
import { RedisStore } from "rate-limit-redis";
import { createClient } from "redis";
import { csrfToken, matchLegacyRoute, toCamel, toSnake } from "./legacy-routes.js";

const required = ["DATABASE_URL", "SESSION_SECRET", "KOTLIN_API_URL", "REDIS_URL"];
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
const redisClient = createClient({ url: process.env.REDIS_URL });
redisClient.on("error", () => process.stderr.write("Redis connection error\\n"));
await redisClient.connect();
const createLimiterStore = prefix => new RedisStore({
  sendCommand: (...args) => redisClient.sendCommand(args),
  prefix: "imobpro:ratelimit:" + prefix,
});
const authLimiterStore = createLimiterStore("auth:");
const apiLimiterStore = createLimiterStore("api:");
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

const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 20, standardHeaders: "draft-7", legacyHeaders: false, store: authLimiterStore });
const apiLimiter = rateLimit({ windowMs: 15 * 60 * 1000, limit: 1200, standardHeaders: "draft-7", legacyHeaders: false, store: apiLimiterStore });
const mutationMethods = new Set(["POST", "PUT", "PATCH", "DELETE"]);

function legacyUser(user) {
  if (!user) return null;
  return { id: user.userId || user.id, name: user.fullName || user.name, email: user.email, phone: user.phone || null, role: String(user.role || "").toLowerCase() };
}

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

app.use("/api", apiLimiter);

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
  if (result.status >= 400 && result.data && typeof result.data === "object") {
    const message = result.data.detail || result.data.message || result.data.error || "A operação não foi concluída.";
    return res.status(result.status).json({ error: typeof message === "string" ? message : JSON.stringify(message) });
  }
  res.status(result.status);
  if (result.headers?.get("content-type")) res.set("content-type", result.headers.get("content-type"));
  if (result.data === null || result.data === undefined) return res.end();
  if (typeof result.data === "string") return res.send(result.data);
  return res.json(toSnake(result.data));
}


app.get("/media/:listingSlug/:imageId", async (req, res) => {
  const slug = process.env.PUBLIC_ORGANIZATION_SLUG;
  if (!slug) return res.sendStatus(404);
  try {
    const url = apiBase + "/api/v1/public/" + encodeURIComponent(slug) + "/listings/" +
      encodeURIComponent(req.params.listingSlug) + "/images/" + encodeURIComponent(req.params.imageId);
    const upstream = await fetch(url, { signal: AbortSignal.timeout(15000) });
    if (!upstream.ok) return res.sendStatus(upstream.status);
    res.set("content-type", upstream.headers.get("content-type") || "application/octet-stream");
    res.set("cache-control", "public, max-age=600");
    return res.send(Buffer.from(await upstream.arrayBuffer()));
  } catch { return res.sendStatus(502); }
});

app.get("/api/settings", async (_req, res) => {
  const slug = process.env.PUBLIC_ORGANIZATION_SLUG;
  if (!slug) return fail(res, 503, "PUBLIC_ORGANIZATION_NOT_CONFIGURED", "Configure PUBLIC_ORGANIZATION_SLUG.");
  try { return sendApiResult(res, await apiCall("/api/v1/public/" + encodeURIComponent(slug) + "/settings")); }
  catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "API Kotlin indisponível."); }
});

function legacyPublicProperty(property) {
  const purpose = property.purpose === "RENT" ? "aluguel" : "venda";
  const images = (property.images || []).map(image => ({
    id: image.id,
    filename: "/media/" + encodeURIComponent(property.slug) + "/" + encodeURIComponent(image.id),
    alt_text: image.altText || "",
    position: image.position,
  }));
  return {
    id: property.propertyId,
    listing_id: property.id,
    listing_slug: property.slug,
    title: property.title,
    description: property.description || "",
    type: String(property.propertyType || "OTHER").toLowerCase(),
    purpose,
    city: property.city,
    state: property.state,
    neighborhood: property.neighborhood || "",
    bedrooms: property.bedrooms || 0,
    suites: 0,
    bathrooms: property.bathrooms || 0,
    parking: property.parkingSpaces || 0,
    area: Number(property.builtArea || property.landArea || 0),
    price: Number(property.rentPrice || property.salePrice || 0),
    condo: 0,
    iptu: 0,
    cover_image: images[0]?.filename || "",
    images,
    features: [],
    status: "published",
  };
}

app.get("/api/properties/:id", async (req, res) => {
  const slug = process.env.PUBLIC_ORGANIZATION_SLUG;
  if (!slug) return fail(res, 503, "PUBLIC_ORGANIZATION_NOT_CONFIGURED", "Configure PUBLIC_ORGANIZATION_SLUG.");
  try {
    const result = await apiCall("/api/v1/public/" + encodeURIComponent(slug) + "/listings/by-property/" + encodeURIComponent(req.params.id));
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json(legacyPublicProperty(result.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "API Kotlin indisponível."); }
});

app.get("/api/properties", async (req, res) => {
  const slug = process.env.PUBLIC_ORGANIZATION_SLUG;
  if (!slug) return fail(res, 503, "PUBLIC_ORGANIZATION_NOT_CONFIGURED", "Configure PUBLIC_ORGANIZATION_SLUG.");
  try {
    const query = new URLSearchParams();
    if (req.query.offset) query.set("offset", String(req.query.offset));
    if (req.query.limit) query.set("limit", String(req.query.limit));
    if (req.query.city) query.set("city", String(req.query.city));
    if (req.query.neighborhood) query.set("neighborhood", String(req.query.neighborhood));
    if (req.query.q) query.set("q", String(req.query.q));
    if (req.query.minPrice) query.set("minPrice", String(req.query.minPrice));
    if (req.query.maxPrice) query.set("maxPrice", String(req.query.maxPrice));
    if (req.query.bedrooms) query.set("bedrooms", String(req.query.bedrooms));
    if (req.query.parking) query.set("parkingSpaces", String(req.query.parking));
    if (req.query.type) {
      const type = String(req.query.type).toLowerCase();
      query.set("propertyType", ({ apartamento: "APARTMENT", casa: "HOUSE", comercial: "COMMERCIAL", terreno: "LAND", rural: "RURAL" })[type] || "OTHER");
    }
    if (req.query.purpose) query.set("purpose", String(req.query.purpose).toLowerCase() === "aluguel" ? "RENT" : "SALE");
    const result = await apiCall("/api/v1/public/" + encodeURIComponent(slug) + "/listings" + (query.size ? "?" + query.toString() : ""));
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data?.items || []).map(legacyPublicProperty));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "API Kotlin indisponível."); }
});

app.post("/api/leads", authLimiter, requireCsrf, async (req, res) => {
  const slug = process.env.PUBLIC_ORGANIZATION_SLUG;
  if (!slug) return fail(res, 503, "PUBLIC_ORGANIZATION_NOT_CONFIGURED", "Configure PUBLIC_ORGANIZATION_SLUG.");
  try {
    const result = await apiCall("/api/v1/public/" + encodeURIComponent(slug) + "/leads", {
      method: "POST", body: {
        fullName: req.body?.name || req.body?.fullName,
        phone: req.body?.whatsapp || req.body?.phone,
        email: req.body?.email,
        message: req.body?.message,
        propertyId: req.body?.property_id || req.body?.propertyId || null,
      },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "API Kotlin indisponível."); }
});

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
    if (!credentials.organizationId) {
      const choices = await apiCall("/api/v1/auth/organizations", { method: "POST", body: credentials });
      if (choices.status !== 200) return sendApiResult(res, choices);
      if (!Array.isArray(choices.data) || choices.data.length !== 1) {
        return res.status(409).json({ error: "organization_selection_required", organizations: toSnake(choices.data || []) });
      }
      credentials.organizationId = choices.data[0].organizationId;
    }
    const result = await apiCall("/api/v1/auth/login", { method: "POST", body: credentials });
    if (result.status !== 200) return sendApiResult(res, result);
    await new Promise((resolve, reject) => req.session.regenerate(error => error ? reject(error) : resolve()));
    req.session.accessToken = result.data.accessToken;
    req.session.refreshToken = result.data.refreshToken;
    req.session.accessExpiresAt = result.data.expiresAt;
    const me = await authenticatedCall(req, "/api/v1/auth/me");
    if (me.status !== 200) return sendApiResult(res, me);
    req.session.organizationId = me.data.organizationId;
    return res.status(200).json(legacyUser(me.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível concluir o login."); }
});

app.post("/api/auth/register", authLimiter, requireCsrf, async (req, res) => {
  const slug = process.env.PUBLIC_ORGANIZATION_SLUG;
  if (!slug) return fail(res, 503, "PUBLIC_ORGANIZATION_NOT_CONFIGURED", "Configure PUBLIC_ORGANIZATION_SLUG.");
  try {
    const body = req.body || {};
    const result = await apiCall("/api/v1/public/" + encodeURIComponent(slug) + "/customers/register", {
      method: "POST",
      body: {
        fullName: body.name || body.fullName,
        email: body.email,
        password: body.password,
        phone: body.phone || body.whatsapp || null,
      },
    });
    if (result.status !== 200) return sendApiResult(res, result);
    await new Promise((resolve, reject) => req.session.regenerate(error => error ? reject(error) : resolve()));
    req.session.accessToken = result.data.accessToken;
    req.session.refreshToken = result.data.refreshToken;
    req.session.accessExpiresAt = result.data.expiresAt;
    const me = await authenticatedCall(req, "/api/v1/auth/me");
    if (me.status !== 200) return sendApiResult(res, me);
    req.session.organizationId = me.data.organizationId;
    return res.status(201).json(legacyUser(me.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível concluir o cadastro."); }
});

app.post("/api/auth/admin-recover", authLimiter, requireCsrf, async (_req, res) => {
  return fail(res, 404, "ROUTE_NOT_AVAILABLE", "A recuperação administrativa por chave compartilhada foi desativada. Use recuperação individual de senha e o fluxo de convite/ownership.");
});

app.post("/api/auth/forgot-password", authLimiter, requireCsrf, async (req, res) => {
  try {
    return sendApiResult(res, await apiCall("/api/v1/auth/password/forgot", {
      method: "POST", body: { email: req.body?.email },
    }));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Serviço de recuperação indisponível."); }
});

app.post("/api/auth/reset-password", authLimiter, requireCsrf, async (req, res) => {
  try {
    return sendApiResult(res, await apiCall("/api/v1/auth/password/reset", {
      method: "POST", body: { token: req.body?.token, newPassword: req.body?.password || req.body?.newPassword },
    }));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Serviço de recuperação indisponível."); }
});

app.post("/api/auth/change-password", requireCsrf, async (req, res) => {
  try {
    return sendApiResult(res, await authenticatedCall(req, "/api/v1/me/password", {
      method: "POST", body: toCamel(req.body || {}),
    }));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível alterar a senha."); }
});

app.get("/api/auth/me", async (req, res) => {
  if (!req.session.accessToken) return res.json(null);
  try {
    const result = await authenticatedCall(req, "/api/v1/auth/me");
    if (result.status === 401) return res.json(null);
    if (result.status === 401) return res.json(null);
    return res.status(200).json(legacyUser(result.data));
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

app.get("/api/messages/contacts", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/conversations/contacts");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data || []).map(person => ({
      id: person.userId,
      name: person.fullName,
      role: String(person.role || "").toLowerCase(),
    })));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Chat indisponível."); }
});

app.get("/api/messages/unread", async (req, res) => {
  try { return sendApiResult(res, await authenticatedCall(req, "/api/v1/conversations/unread")); }
  catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Chat indisponível."); }
});

app.get("/api/messages", async (req, res) => {
  const withUser = req.query.with;
  if (!withUser) return res.json([]);
  try {
    const conversations = await authenticatedCall(req, "/api/v1/conversations?withUserId=" + encodeURIComponent(withUser));
    if (conversations.status !== 200) return sendApiResult(res, conversations);
    if (!Array.isArray(conversations.data) || !conversations.data.length) return res.json([]);
    const result = await authenticatedCall(req, "/api/v1/conversations/" + encodeURIComponent(conversations.data[0].id) + "/messages");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data || []).map(message => ({
      id: message.id,
      sender_id: message.senderUserId,
      body: message.body,
      created_at: message.createdAt,
    })));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Chat indisponível."); }
});

app.post("/api/messages", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    const recipientId = body.recipient_id || body.recipientId;
    if (!recipientId || !body.body) return fail(res, 400, "INVALID_MESSAGE", "Destinatário e mensagem são obrigatórios.");
    const query = await authenticatedCall(req, "/api/v1/conversations?withUserId=" + encodeURIComponent(recipientId));
    if (query.status !== 200) return sendApiResult(res, query);
    let conversation = Array.isArray(query.data) ? query.data[0] : null;
    if (!conversation) {
      const created = await authenticatedCall(req, "/api/v1/conversations", {
        method: "POST",
        body: {
          subject: body.property_id ? "Interesse em imóvel " + body.property_id : "Atendimento",
          leadId: body.lead_id || null,
          participantUserIds: [recipientId],
        },
      });
      if (created.status !== 201) return sendApiResult(res, created);
      conversation = created.data;
    }
    const sent = await authenticatedCall(req, "/api/v1/conversations/" + encodeURIComponent(conversation.id) + "/messages", {
      method: "POST", body: { body: body.body },
    });
    if (sent.status !== 201) return sendApiResult(res, sent);
    return res.status(201).json({
      id: sent.data.id,
      sender_id: sent.data.senderUserId,
      body: sent.data.body,
      created_at: sent.data.createdAt,
    });
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível enviar a mensagem."); }
});


function legacyStatus(value) {
  return String(value || "").toLowerCase();
}
function legacyLead(item) {
  return {
    id: item.id,
    name: item.fullName,
    email: item.email,
    whatsapp: item.phone,
    status: legacyStatus(item.status),
    message: item.message,
    property_id: item.propertyId,
    assigned_to: item.assignedToUserId,
    created_at: item.createdAt,
  };
}
function legacyTask(item) {
  return {
    id: item.id,
    title: item.title,
    notes: item.description,
    status: legacyStatus(item.status),
    priority: legacyStatus(item.priority),
    due_at: item.dueAt,
    user_id: item.assignedToUserId,
    lead_id: item.leadId,
    property_id: item.propertyId,
    lead_name: null,
    property_title: null,
    created_at: item.createdAt,
  };
}
function legacyVisit(item) {
  return {
    id: item.id,
    property_id: item.propertyId,
    lead_id: item.leadId,
    agent_id: item.assignedToUserId,
    visit_date: item.scheduledAt,
    status: legacyStatus(item.status),
    notes: item.notes,
    created_at: item.createdAt,
  };
}
function legacyPropertyFromPrivate(item, images = [], listingStatus = null) {
  const purpose = item.purpose === "RENT" ? "aluguel" : "venda";
  const mappedImages = images.map(image => ({
    id: image.id,
    filename: "/media/property/" + encodeURIComponent(item.id) + "/" + encodeURIComponent(image.id),
    alt_text: image.altText || "",
    position: image.position,
  }));
  return {
    id: item.id,
    title: item.title,
    description: item.description || "",
    type: ({ APARTMENT: "Apartamento", HOUSE: "Casa", COMMERCIAL: "Comercial", LAND: "Terreno", RURAL: "Rural" })[item.propertyType] || "Outro",
    purpose,
    status: legacyStatus(listingStatus || item.status),
    city: item.city,
    state: item.state,
    neighborhood: item.neighborhood || "",
    address: [item.street, item.streetNumber, item.complement].filter(Boolean).join(" "),
    bedrooms: item.bedrooms || 0,
    suites: 0,
    bathrooms: item.bathrooms || 0,
    parking: item.parkingSpaces || 0,
    area: Number(item.builtArea || item.landArea || 0),
    price: Number(item.rentPrice || item.salePrice || 0),
    condo: 0,
    iptu: 0,
    cover_image: mappedImages[0]?.filename || "",
    images: mappedImages,
    features: [],
    owner_name: "Equipe",
  };
}
async function loadLegacyProperties(req) {
  const [properties, listings] = await Promise.all([
    authenticatedCall(req, "/api/v1/properties?offset=0&limit=100"),
    authenticatedCall(req, "/api/v1/listings?offset=0&limit=100"),
  ]);
  if (properties.status !== 200) return properties;
  const listingStatus = new Map((listings.data || []).map(item => [item.propertyId, item.status]));
  const items = properties.data?.items || [];
  const mapped = await Promise.all(items.map(async item => {
    const imageResult = await authenticatedCall(req, "/api/v1/properties/" + encodeURIComponent(item.id) + "/images");
    return legacyPropertyFromPrivate(item, imageResult.status === 200 ? imageResult.data || [] : [], listingStatus.get(item.id));
  }));
  return { status: 200, data: mapped };
}

app.get("/api/admin/stats", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/dashboard/summary");
    if (result.status !== 200) return sendApiResult(res, result);
    const value = result.data;
    return res.json({
      properties: value.properties,
      published: value.publishedListings,
      leads: value.leads,
      newLeads: value.newLeads,
      visits: value.upcomingVisits,
      owners: value.owners,
      customers: value.customers,
      agents: value.agents,
    });
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Dashboard indisponível."); }
});

app.get("/api/agent/summary", async (req, res) => {
  try {
    const [leads, tasks, unread] = await Promise.all([
      authenticatedCall(req, "/api/v1/leads?assignedToMe=true&limit=100"),
      authenticatedCall(req, "/api/v1/crm/tasks?assignedToMe=true&limit=100"),
      authenticatedCall(req, "/api/v1/conversations/unread"),
    ]);
    if ([leads, tasks, unread].some(result => result.status !== 200)) return sendApiResult(res, [leads, tasks, unread].find(result => result.status !== 200));
    const leadItems = leads.data?.items || [];
    const taskItems = tasks.data?.items || [];
    return res.json({
      leads: leadItems.length,
      openLeads: leadItems.filter(item => !["CLOSED", "CONVERTED", "LOST"].includes(item.status)).length,
      tasks: taskItems.filter(item => ["OPEN", "IN_PROGRESS"].includes(item.status)).length,
      unread: unread.data?.count || 0,
    });
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Dashboard do agente indisponível."); }
});

for (const endpoint of ["/api/admin/leads", "/api/agent/leads"]) {
  app.get(endpoint, async (req, res) => {
    try {
      const assigned = endpoint.includes("/agent/");
      const result = await authenticatedCall(req, "/api/v1/leads?limit=100" + (assigned ? "&assignedToMe=true" : ""));
      if (result.status !== 200) return sendApiResult(res, result);
      return res.json((result.data?.items || []).map(legacyLead));
    } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Leads indisponíveis."); }
  });
}

for (const endpoint of ["/api/admin/tasks", "/api/agent/tasks"]) {
  app.get(endpoint, async (req, res) => {
    try {
      const assigned = endpoint.includes("/agent/");
      const result = await authenticatedCall(req, "/api/v1/crm/tasks?limit=100" + (assigned ? "&assignedToMe=true" : ""));
      if (result.status !== 200) return sendApiResult(res, result);
      return res.json((result.data?.items || []).map(legacyTask));
    } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Tarefas indisponíveis."); }
  });
}

app.get("/api/admin/visits", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/crm/visits?limit=100");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data?.items || []).map(legacyVisit));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Visitas indisponíveis."); }
});

for (const endpoint of ["/api/admin/properties", "/api/owner/properties"]) {
  app.get(endpoint, async (req, res) => {
    try {
      const result = await loadLegacyProperties(req);
      return result.status === 200 ? res.json(result.data) : sendApiResult(res, result);
    } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Imóveis indisponíveis."); }
  });
}

app.post("/api/admin/properties", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    const purpose = String(body.purpose || "venda").toLowerCase() === "aluguel" ? "RENT" : "SALE";
    const type = ({ apartamento: "APARTMENT", casa: "HOUSE", comercial: "COMMERCIAL", terreno: "LAND", rural: "RURAL" })[String(body.type || "").toLowerCase()] || "OTHER";
    const result = await authenticatedCall(req, "/api/v1/properties", {
      method: "POST",
      body: {
        propertyType: type, purpose, title: body.title, description: body.description,
        city: body.city, state: body.state || "CE", neighborhood: body.neighborhood,
        street: body.address, bedrooms: Number(body.bedrooms) || null,
        bathrooms: Number(body.bathrooms) || null, parkingSpaces: Number(body.parking) || null,
        builtArea: Number(body.area) || null,
        salePrice: purpose === "SALE" ? Number(body.price) || null : null,
        rentPrice: purpose === "RENT" ? Number(body.price) || null : null,
      },
    });
    if (result.status !== 201) return sendApiResult(res, result);
    return res.status(201).json(legacyPropertyFromPrivate(result.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível cadastrar o imóvel."); }
});

app.post("/api/owner/properties", requireCsrf, async (req, res) => {
  req.url = "/api/admin/properties";
  return res.redirect(307, "/api/admin/properties");
});

for (const endpoint of ["/api/admin/properties/:id", "/api/owner/properties/:id"]) {
  app.patch(endpoint, requireCsrf, async (req, res) => {
    try {
      const body = req.body || {};
      const purpose = String(body.purpose || "venda").toLowerCase() === "aluguel" ? "RENT" : "SALE";
      const type = ({ apartamento: "APARTMENT", casa: "HOUSE", comercial: "COMMERCIAL", terreno: "LAND", rural: "RURAL" })[String(body.type || "").toLowerCase()] || "OTHER";
      const result = await authenticatedCall(req, "/api/v1/properties/" + encodeURIComponent(req.params.id), {
        method: "PATCH",
        body: {
          propertyType: type, purpose, title: body.title, description: body.description,
          city: body.city, state: body.state || "CE", neighborhood: body.neighborhood,
          street: body.address, bedrooms: Number(body.bedrooms) || null,
          bathrooms: Number(body.bathrooms) || null, parkingSpaces: Number(body.parking) || null,
          builtArea: Number(body.area) || null,
          salePrice: purpose === "SALE" ? Number(body.price) || null : null,
          rentPrice: purpose === "RENT" ? Number(body.price) || null : null,
        },
      });
      if (result.status !== 200) return sendApiResult(res, result);
      return res.json(legacyPropertyFromPrivate(result.data));
    } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível atualizar o imóvel."); }
  });
}

app.patch("/api/admin/properties/:id/status", requireCsrf, async (req, res) => {
  try {
    const status = String(req.body?.status || "").toUpperCase();
    const result = await authenticatedCall(req, "/api/v1/listings/by-property/" + encodeURIComponent(req.params.id) + "/status", {
      method: "PATCH", body: { status },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível atualizar o anúncio."); }
});

app.post("/api/properties/:id/view", requireCsrf, async (req, res) => {
  const slug = process.env.PUBLIC_ORGANIZATION_SLUG;
  if (!slug) return fail(res, 503, "PUBLIC_ORGANIZATION_NOT_CONFIGURED", "Configure PUBLIC_ORGANIZATION_SLUG.");
  try {
    const result = await apiCall("/api/v1/public/" + encodeURIComponent(slug) + "/properties/" +
      encodeURIComponent(req.params.id) + "/view", {
      method: "POST", body: { source: "DIRECT" },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível registrar a visualização."); }
});

app.post("/api/visits", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    const result = await authenticatedCall(req, "/api/v1/me/visit-requests", {
      method: "POST",
      body: {
        propertyId: body.property_id || body.propertyId,
        scheduledAt: body.visit_date || body.scheduledAt,
        notes: body.notes || null,
      },
    });
    if (result.status !== 201) return sendApiResult(res, result);
    return res.status(201).json(legacyVisit(result.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível solicitar a visita."); }
});

app.post("/api/admin/visits", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    const result = await authenticatedCall(req, "/api/v1/crm/visits", {
      method: "POST",
      body: {
        propertyId: body.property_id || body.propertyId,
        leadId: body.lead_id || body.leadId || null,
        assignedToUserId: body.agent_id || body.user_id || body.assigned_to || null,
        scheduledAt: body.visit_date || body.scheduled_at || body.scheduledAt,
        durationMinutes: Number(body.duration_minutes || 60),
        notes: body.notes || null,
      },
    });
    if (result.status !== 201) return sendApiResult(res, result);
    return res.status(201).json(legacyVisit(result.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível agendar a visita."); }
});

app.post("/api/admin/tasks", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    const result = await authenticatedCall(req, "/api/v1/crm/tasks", {
      method: "POST",
      body: {
        title: body.title, description: body.notes || body.description || null,
        assignedToUserId: body.user_id || body.assigned_to || null,
        leadId: body.lead_id || null, propertyId: body.property_id || null,
        dueAt: body.due_at || null, priority: String(body.priority || "normal").toUpperCase(),
      },
    });
    if (result.status !== 201) return sendApiResult(res, result);
    return res.status(201).json(legacyTask(result.data));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível criar a tarefa."); }
});

for (const pattern of ["/api/admin/tasks/:id", "/api/agent/tasks/:id"]) {
  app.patch(pattern, requireCsrf, async (req, res) => {
    try {
      const status = String(req.body?.status || "done").toUpperCase();
      const result = await authenticatedCall(req, "/api/v1/crm/tasks/" + encodeURIComponent(req.params.id) + "/status", {
        method: "PATCH", body: { status: status === "DONE" ? "DONE" : status === "OPEN" ? "OPEN" : "IN_PROGRESS" },
      });
      return sendApiResult(res, result);
    } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível atualizar a tarefa."); }
  });
}

app.patch("/api/admin/leads/:id", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    let result;
    if (body.assigned_to || body.assignedToUserId) {
      result = await authenticatedCall(req, "/api/v1/leads/" + encodeURIComponent(req.params.id) + "/assignment", {
        method: "PATCH", body: { assignedToUserId: body.assigned_to || body.assignedToUserId },
      });
    } else {
      const status = String(body.status || "contacted").toUpperCase();
      const mapped = ({ VISIT: "QUALIFIED", PROPOSAL: "QUALIFIED", NEGOTIATION: "QUALIFIED", CLOSED: "CONVERTED" })[status] || status;
      result = await authenticatedCall(req, "/api/v1/leads/" + encodeURIComponent(req.params.id) + "/status", {
        method: "PATCH", body: { status: mapped },
      });
    }
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível atualizar o lead."); }
});

app.get("/media/property/:propertyId/:imageId", async (req, res) => {
  try {
    const path = "/api/v1/properties/" + encodeURIComponent(req.params.propertyId) +
      "/images/" + encodeURIComponent(req.params.imageId) + "/content";
    if (!req.session.accessToken) return res.sendStatus(401);
    let upstream = await fetch(apiBase + path, {
      headers: { authorization: "Bearer " + req.session.accessToken },
      signal: AbortSignal.timeout(15000),
    });
    if (upstream.status === 401 && await refreshSession(req)) {
      upstream = await fetch(apiBase + path, {
        headers: { authorization: "Bearer " + req.session.accessToken },
        signal: AbortSignal.timeout(15000),
      });
    }
    if (!upstream.ok) return res.sendStatus(upstream.status);
    res.set("content-type", upstream.headers.get("content-type") || "application/octet-stream");
    res.set("cache-control", "private, max-age=300");
    return res.send(Buffer.from(await upstream.arrayBuffer()));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Imagem indisponível."); }
});



app.get("/api/me/profile", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/me/profile");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json({
      id: result.data.userId,
      name: result.data.fullName,
      email: result.data.email,
      phone: result.data.phone,
      role: String(result.data.role || "").toLowerCase(),
    });
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Perfil indisponível."); }
});

app.patch("/api/me/profile", requireCsrf, async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/me/profile", {
      method: "PATCH",
      body: {
        fullName: req.body?.name || req.body?.fullName,
        phone: req.body?.phone || null,
      },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível atualizar o perfil."); }
});


app.post("/api/admin/automations", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    const result = await authenticatedCall(req, "/api/v1/automations", {
      method: "POST",
      body: {
        name: body.name,
        triggerKey: String(body.trigger_key || body.triggerKey || "").toUpperCase(),
        actionKey: String(body.action_key || body.actionKey || "").toUpperCase(),
        configuration: body.configuration || {},
      },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível criar a automação."); }
});

app.patch("/api/admin/automations/:id", requireCsrf, async (req, res) => {
  try {
    const enabled = req.body?.enabled === true || req.body?.enabled === 1 || req.body?.enabled === "1" || req.body?.enabled === "true";
    const result = await authenticatedCall(req, "/api/v1/automations/" + encodeURIComponent(req.params.id) + "/enabled", {
      method: "PATCH", body: { enabled },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível atualizar a automação."); }
});

app.patch("/api/admin/settings", requireCsrf, async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/organizations/current/settings", {
      method: "PATCH", body: { settings: req.body || {} },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível salvar as configurações."); }
});

app.get("/api/admin/users", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/organizations/current/members");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data || []).map(member => ({
      id: member.userId, name: member.fullName, email: member.email,
      role: String(member.role || "").toLowerCase(), status: String(member.status || "").toLowerCase(),
      joined_at: member.joinedAt,
    })));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Equipe indisponível."); }
});

app.get("/api/me/favorites", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/me/favorites");
    if (result.status !== 200) return sendApiResult(res, result);
    const items = await Promise.all((result.data || []).map(async favorite => {
      const property = await authenticatedCall(req, "/api/v1/properties/" + encodeURIComponent(favorite.propertyId));
      if (property.status !== 200) return null;
      const images = await authenticatedCall(req, "/api/v1/properties/" + encodeURIComponent(favorite.propertyId) + "/images");
      const listing = await authenticatedCall(req, "/api/v1/listings?limit=100");
      const status = (listing.data || []).find(item => item.propertyId === favorite.propertyId)?.status;
      return legacyPropertyFromPrivate(property.data, images.status === 200 ? images.data || [] : [], status);
    }));
    return res.json(items.filter(Boolean));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Favoritos indisponíveis."); }
});

app.get("/api/me/notifications", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/notifications?limit=50");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data?.items || []).map(item => ({
      id: item.id, title: item.title, message: item.body || "",
      read_at: item.readAt, created_at: item.createdAt,
    })));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Notificações indisponíveis."); }
});

app.get("/api/me/collections", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/me/collections");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data || []).map(collection => ({
      id: collection.id, name: collection.name, description: collection.description,
      created_at: collection.createdAt, updated_at: collection.updatedAt,
      items: (collection.items || []).map(item => ({
        id: item.propertyId, property_id: item.propertyId, title: item.title,
        city: item.city, state: item.state, price: Number(item.rentPrice || item.salePrice || 0),
        purpose: item.rentPrice ? "aluguel" : "venda", neighborhood: "",
        bedrooms: 0, suites: 0, bathrooms: 0, parking: 0, area: 0,
        cover_image: "", images: [], description: item.comment || "",
      })),
    })));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Coleções indisponíveis."); }
});

app.get("/api/me/saved-searches", async (req, res) => {
  try {
    const result = await authenticatedCall(req, "/api/v1/me/saved-searches");
    if (result.status !== 200) return sendApiResult(res, result);
    return res.json((result.data || []).map(item => ({
      id: item.id, name: item.name, filters: item.criteria,
      frequency: item.alertsEnabled ? "daily" : "manual", created_at: item.createdAt,
    })));
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Buscas salvas indisponíveis."); }
});

app.post("/api/me/saved-searches", requireCsrf, async (req, res) => {
  try {
    const body = req.body || {};
    const result = await authenticatedCall(req, "/api/v1/me/saved-searches", {
      method: "POST",
      body: {
        name: body.name || "Minha busca",
        criteria: body.filters || body.criteria || {},
        alertsEnabled: String(body.frequency || "").toLowerCase() !== "manual",
      },
    });
    return sendApiResult(res, result);
  } catch { return fail(res, 502, "UPSTREAM_UNAVAILABLE", "Não foi possível salvar a busca."); }
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


app.use(async (req, res) => {
  if (req.path.startsWith("/api")) return fail(res, 501, "LEGACY_ROUTE_NOT_MAPPED", "Endpoint legado ainda sem adaptador validado.");
  if (!process.env.LEGACY_WEB_URL || !["GET", "HEAD"].includes(req.method)) {
    return fail(res, 404, "NOT_FOUND", "Rota não encontrada.");
  }
  try {
    const upstreamUrl = new URL(req.originalUrl, process.env.LEGACY_WEB_URL);
    const allowedOrigin = new URL(process.env.LEGACY_WEB_URL).origin;
    if (upstreamUrl.origin !== allowedOrigin) return fail(res, 400, "INVALID_UPSTREAM", "Destino inválido.");
    const upstream = await fetch(upstreamUrl, { method: req.method, redirect: "manual", signal: AbortSignal.timeout(10000) });
    res.status(upstream.status);
    for (const header of ["content-type", "cache-control", "etag", "last-modified"]) {
      const value = upstream.headers.get(header);
      if (value) res.set(header, value);
    }
    if (req.method === "HEAD") return res.end();
    return res.send(Buffer.from(await upstream.arrayBuffer()));
  } catch { return fail(res, 502, "LEGACY_WEB_UNAVAILABLE", "A interface web legada está indisponível."); }
});

app.use((error, _req, res, _next) => {
  if (error?.type === "entity.too.large") return fail(res, 413, "PAYLOAD_TOO_LARGE", "Corpo excede o limite permitido.");
  return fail(res, 500, "INTERNAL_ERROR", "Erro interno.");
});

const port = Number(process.env.PORT || 3100);
app.listen(port, "0.0.0.0", () => process.stdout.write("Imob-Pro compatibility BFF listening on " + port + "\n"));
