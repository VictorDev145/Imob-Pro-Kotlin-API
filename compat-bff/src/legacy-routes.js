import crypto from "node:crypto";

const definitions = [
  ["GET", /^\/api\/health$/, "/api/v1/health"],
  ["GET", /^\/api\/admin\/stats$/, "/api/v1/dashboard/summary"],
  ["GET", /^\/api\/admin\/activity$/, "/api/v1/dashboard/activity"],
  ["GET", /^\/api\/admin\/tasks$/, "/api/v1/crm/tasks"],
  ["POST", /^\/api\/admin\/tasks$/, "/api/v1/crm/tasks"],
  ["PATCH", /^\/api\/admin\/tasks\/([^/]+)$/, "/api/v1/crm/tasks/$1/status"],
  ["GET", /^\/api\/admin\/visits$/, "/api/v1/crm/visits"],
  ["POST", /^\/api\/admin\/visits$/, "/api/v1/crm/visits"],
  ["GET", /^\/api\/me\/favorites$/, "/api/v1/me/favorites"],
  ["POST", /^\/api\/me\/favorites\/([^/]+)$/, "/api/v1/me/favorites/$1"],
  ["DELETE", /^\/api\/me\/favorites\/([^/]+)$/, "/api/v1/me/favorites/$1"],
  ["GET", /^\/api\/me\/collections$/, "/api/v1/me/collections"],
  ["POST", /^\/api\/me\/collections$/, "/api/v1/me/collections"],
  ["POST", /^\/api\/me\/collections\/([^/]+)\/items$/, "/api/v1/me/collections/$1/items"],
  ["DELETE", /^\/api\/me\/collections\/([^/]+)\/items\/([^/]+)$/, "/api/v1/me/collections/$1/items/$2"],
  ["GET", /^\/api\/me\/saved-searches$/, "/api/v1/me/saved-searches"],
  ["POST", /^\/api\/me\/saved-searches$/, "/api/v1/me/saved-searches"],
  ["DELETE", /^\/api\/me\/saved-searches\/([^/]+)$/, "/api/v1/me/saved-searches/$1"],
  ["GET", /^\/api\/me\/notifications$/, "/api/v1/notifications"],
  ["PATCH", /^\/api\/me\/notifications\/([^/]+)\/read$/, "/api/v1/notifications/$1/read"],
  ["GET", /^\/api\/me\/profile$/, "/api/v1/me/profile"],
  ["PATCH", /^\/api\/me\/profile$/, "/api/v1/me/profile"],
  ["GET", /^\/api\/me\/export$/, "/api/v1/me/export"],
  ["POST", /^\/api\/me\/privacy-requests$/, "/api/v1/me/privacy-requests"],
  ["GET", /^\/api\/admin\/privacy-requests$/, "/api/v1/privacy-requests"],
  ["GET", /^\/api\/admin\/automations$/, "/api/v1/automations"],
  ["POST", /^\/api\/admin\/automations$/, "/api/v1/automations"],
  ["PATCH", /^\/api\/admin\/automations\/([^/]+)$/, "/api/v1/automations/$1/enabled"],
  ["GET", /^\/api\/properties$/, "/api/v1/properties"],
  ["POST", /^\/api\/admin\/properties$/, "/api/v1/properties"],
  ["GET", /^\/api\/admin\/properties$/, "/api/v1/properties"],
  ["PATCH", /^\/api\/admin\/properties\/([^/]+)$/, "/api/v1/properties/$1"],
  ["POST", /^\/api\/admin\/properties\/([^/]+)\/images$/, "/api/v1/properties/$1/images"],
  ["GET", /^\/api\/admin\/leads$/, "/api/v1/leads"],
  ["PATCH", /^\/api\/admin\/leads\/([^/]+)$/, "/api/v1/leads/$1"],
  ["GET", /^\/api\/agent\/leads$/, "/api/v1/leads"],
  ["GET", /^\/api\/agent\/tasks$/, "/api/v1/crm/tasks"],
  ["POST", /^\/api\/auth\/change-password$/, "/api/v1/me/password"],
  ["POST", /^\/api\/auth\/forgot-password$/, "/api/v1/auth/password/forgot"],
  ["POST", /^\/api\/auth\/reset-password$/, "/api/v1/auth/password/reset"],
  ["GET", /^\/api\/admin\/users$/, "/api/v1/organizations/current/members"],
  ["POST", /^\/api\/admin\/users$/, "/api/v1/organizations/current/invitations"],
  ["PATCH", /^\/api\/admin\/users\/([^/]+)$/, "/api/v1/organizations/current/members/$1/role"],
  ["DELETE", /^\/api\/admin\/users\/([^/]+)$/, "/api/v1/organizations/current/members/$1"],
  ["GET", /^\/api\/agent\/leads$/, "/api/v1/leads?assignedToMe=true"],
  ["GET", /^\/api\/agent\/tasks$/, "/api/v1/crm/tasks?assignedToMe=true"],
  ["PATCH", /^\/api\/agent\/tasks\/([^/]+)$/, "/api/v1/crm/tasks/$1/status"],
  ["GET", /^\/api\/admin\/messages$/, "/api/v1/conversations/admin/messages"],
  ["GET", /^\/api\/owner\/properties$/, "/api/v1/properties"],
  ["POST", /^\/api\/owner\/properties$/, "/api/v1/properties"],
  ["PATCH", /^\/api\/owner\/properties\/([^/]+)$/, "/api/v1/properties/$1"],
  ["POST", /^\/api\/visits$/, "/api/v1/crm/visits"],
  ["PATCH", /^\/api\/admin\/properties\/([^/]+)\/status$/, "/api/v1/listings/by-property/$1/status"],
];

export function matchLegacyRoute(method, pathname) {
  for (const [verb, pattern, target] of definitions) {
    if (verb !== method.toUpperCase()) continue;
    const match = pathname.match(pattern);
    if (match) {
      let index = 0;
      const rewritten = target.replace(/\$(\d+)/g, (_, n) => {
        const value = match[Number(n)];
        index += 1;
        return encodeURIComponent(value);
      });
      return { target: rewritten, method: verb };
    }
  }
  return null;
}

export const registeredLegacyRouteCount = definitions.length;

export function toCamel(value) {
  if (Array.isArray(value)) return value.map(toCamel);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [
    key.replace(/_([a-z])/g, (_, char) => char.toUpperCase()),
    toCamel(item),
  ]));
}

export function toSnake(value) {
  if (Array.isArray(value)) return value.map(toSnake);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [
    key.replace(/[A-Z]/g, char => `_${char.toLowerCase()}`),
    toSnake(item),
  ]));
}

export function csrfToken() {
  return crypto.randomBytes(32).toString("base64url");
}
