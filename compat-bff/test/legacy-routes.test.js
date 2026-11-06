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
  assert.deepEqual(matchLegacyRoute("DELETE", "/api/admin/users/123"), {
    target: "/api/v1/organizations/current/members/123",
    method: "DELETE",
  });
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

test("property analytics compatibility handler preserves the legacy response contract", () => {
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  assert.match(server, /app\.get\("\/api\/admin\/property-analytics\/:id"/);
  assert.match(server, /"\/api\/v1\/analytics\/properties\/" \+ encodeURIComponent\(req\.params\.id\)/);
  for (const field of ["property:", "total:", "unique:", "favorites:", "leads:", "visits:"]) {
    assert.ok(server.includes(field), `missing legacy analytics field: ${field}`);
  }
});


test("activity compatibility adapter restores the legacy audit fields", () => {
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  assert.match(server, /app\.get\("\/api\/admin\/activity"/);
  for (const field of ["user_id:", "user_name:", "entity_type:", "entity_id:", "details:", "created_at:"]) {
    assert.ok(server.includes(field), `missing legacy activity field: ${field}`);
  }
  assert.match(server, /dashboard\/activity\?limit=100/);
});

test("message compatibility preserves legacy read and length behavior", () => {
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  assert.match(server, /conversations\/" \+ encodeURIComponent\(conversationId\) \+ "\/read/);
  assert.match(server, /MESSAGE_TOO_LONG/);
  assert.match(server, /length > 2000/);
});

test("chat compatibility maps numeric contact IDs to stable UUID aliases", () => {
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  assert.ok(server.includes("async function legacyIdForUser"));
  assert.ok(server.includes("async function userIdForLegacyId"));
  assert.ok(server.includes("id: await legacyIdForUser(person.userId)"));
  assert.ok(server.includes("const recipientId = await userIdForLegacyId(legacyRecipientId)"));
  assert.ok(server.includes("legacy_user_id_map"));
});


test("chat compatibility marks incoming messages as read, not outgoing messages", () => {
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  assert.match(
    server,
    /read_at: message\.senderUserId === recipientId \? message\.createdAt : null/,
  );
});


test("invitation links have a first-party acceptance page and CSRF-protected API adapter", () => {
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  assert.match(server, /app\.get\("\/accept-invitation"/);
  assert.match(server, /app\.post\("\/api\/auth\/invitations\/accept", authLimiter, requireCsrf/);
  assert.match(server, /apiCall\("\/api\/v1\/auth\/invitations\/accept"/);
  assert.match(server, /const invitationPage = `<!doctype html>/);
  assert.match(server, /new URLSearchParams\(location\.search\)\.get\("token"\)/);
});


test("legacy owner accounts map to scoped PROPERTY_OWNER invitations", () => {
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  const propertyService = readFileSync(
    new URL("../../src/main/kotlin/br/com/imobpro/properties/PropertyService.kt", import.meta.url),
    "utf8",
  );
  const propertyImages = readFileSync(
    new URL("../../src/main/kotlin/br/com/imobpro/properties/PropertyImageController.kt", import.meta.url),
    "utf8",
  );
  const membershipMigration = readFileSync(
    new URL("../../src/main/resources/db/migration/V14__property_owner_membership_role.sql", import.meta.url),
    "utf8",
  );
  assert.match(server, /owner: "PROPERTY_OWNER"/);
  assert.match(server, /PROPERTY_OWNER" \? "owner"/);
  assert.match(server, /body: \{ email: body\.email, role \}/);
  assert.match(propertyService, /if \(principal\.role == "PROPERTY_OWNER"\) filters\.add\("created_by = :createdBy"\)/);
  assert.match(propertyService, /ownerScope", principal\.role == "PROPERTY_OWNER"/);
});


test("property owner membership migration and image access are present", () => {
  const propertyImages = readFileSync(
    new URL("../../src/main/kotlin/br/com/imobpro/properties/PropertyImageController.kt", import.meta.url),
    "utf8",
  );
  const migration = readFileSync(
    new URL("../../src/main/resources/db/migration/V14__property_owner_membership_role.sql", import.meta.url),
    "utf8",
  );
  assert.match(propertyImages, /PROPERTY_OWNER/);
  assert.match(migration, /PROPERTY_OWNER/);
});


test("every enabled legacy contract has a concrete adapter or route mapping", () => {
  const coverage = JSON.parse(readFileSync(new URL("../legacy-route-coverage.json", import.meta.url), "utf8"));
  const server = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  for (const contract of coverage) {
    if (contract.status === "disabled") continue;
    if (contract.status === "mapped") {
      const samplePath = contract.path.replace(/:[^/]+/g, "contract-id");
      assert.ok(
        matchLegacyRoute(contract.method, samplePath),
        `missing route mapping: ${contract.method} ${contract.path}`,
      );
    } else {
      assert.ok(
        server.includes(JSON.stringify(contract.path)) || server.includes("'" + contract.path + "'"),
        `missing explicit BFF adapter: ${contract.method} ${contract.path}`,
      );
    }
  }
});
