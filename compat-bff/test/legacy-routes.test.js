import test from "node:test";
import assert from "node:assert/strict";
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
