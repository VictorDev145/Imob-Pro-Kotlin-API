const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const source = fs.readFileSync(path.join(__dirname, "..", "app.js"), "utf8");

test("mutating API calls obtain and send a same-origin CSRF token", () => {
  assert.match(source, /fetch\("\/api\/csrf"/);
  assert.match(source, /headers\["x-csrf-token"\]=csrf\.csrfToken/);
});

test("legacy inline handlers pass UUID entity IDs as strings", () => {
  assert.match(source, /JSON\.stringify\(p\.id\)/);
  assert.match(source, /JSON\.stringify\(l\.id\)/);
  assert.doesNotMatch(source, /onclick="[^"]*\$\{(?:p|l|t|u|x|n|r)\.id\}[^"]*"/);
});

test("visit scheduling uses UUID-backed selects and ISO timestamps", () => {
  assert.match(source, /id="vfProperty" required/);
  assert.match(source, /id="vfLead"/);
  assert.match(source, /scheduledAt\.toISOString\(\)/);
});
