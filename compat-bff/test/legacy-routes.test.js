import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { matchLegacyRoute, registeredLegacyRouteCount, toCamel, toSnake } from "../src/legacy-routes.js";

test("compatibility route registry is explicit and does not invent fallbacks", () => {
  assert.ok(registeredLegacyRouteCount >= 30);
  assert.deepEqual(matchLegacyRoute("GET", "/api/admin/stats"), {
    target: "/api/v1/dashboard/summary",
    method: "GET",
  });
  assert.equal(matchLegacyRoute("DELETE", "/api/admin/users/123"), null);
});

test("path parameters are encoded before forwarding", () => {
  assert.equal(
    matchLegacyRoute("PATCH", "/api/admin/tasks/123").target,
    "/api/v1/crm/tasks/123/status",
  );
  assert.equal(
    matchLegacyRoute("POST", "/api/me/collections/a%2Fb/items").target,
    "/api/v1/me/collections/a%252Fb/items",
  );
});

test("legacy JSON field names are translated recursively", () => {
  assert.deepEqual(toCamel({ property_id: 10, nested_value: [{ user_name: "Ana" }] }), {
    propertyId: 10,
    nestedValue: [{ userName: "Ana" }],
  });
  assert.deepEqual(toSnake({ propertyId: 10, nestedValue: [{ userName: "Ana" }] }), {
    property_id: 10,
    nested_value: [{ user_name: "Ana" }],
  });
});

test("core CRM, privacy and owner routes are explicit", () => {
  assert.equal(matchLegacyRoute("GET", "/api/agent/leads").target, "/api/v1/leads?assignedToMe=true");
  assert.equal(matchLegacyRoute("GET", "/api/agent/tasks").target, "/api/v1/crm/tasks?assignedToMe=true");
  assert.equal(matchLegacyRoute("GET", "/api/admin/property-analytics/abc").target, "/api/v1/analytics/properties/abc");
  assert.equal(matchLegacyRoute("POST", "/api/owner/properties/abc/images").target, "/api/v1/properties/abc/images");
  assert.equal(matchLegacyRoute("PATCH", "/api/admin/properties/abc/status").target, "/api/v1/listings/by-property/abc/status");
});

test("legacy route coverage manifest accounts for all 67 Express contracts", () => {
  const coverage = JSON.parse(readFileSync(new URL("../legacy-route-coverage.json", import.meta.url), "utf8"));
  assert.equal(67, coverage.length);
  assert.equal(1, coverage.filter(item => item.status === "disabled").length);
  assert.deepEqual(
    coverage.filter(item => item.status === "disabled").map(item => item.path),
    ["/api/auth/admin-recover"],
  );
  assert.ok(coverage.every(item => ["adapter", "mapped", "disabled"].includes(item.status)));
});
