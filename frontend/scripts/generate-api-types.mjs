/**
 * OpenAPI type generation — `docs/api-contract.md` is the authority on shapes,
 * and the backend publishes it as a machine-readable document at
 * `/v3/api-docs` (backend `OpenApiConfig`: "/v3/api-docs is a build input").
 *
 * Two artifacts are committed so the frontend builds with no backend running:
 *
 *   openapi.json        the spec snapshot the committed types were cut from
 *   src/lib/api-types.ts  the generated types (never edited by hand)
 *
 * Usage:
 *   node scripts/generate-api-types.mjs              # fetch live spec, rewrite both
 *   node scripts/generate-api-types.mjs --offline    # regenerate types from the snapshot
 *   node scripts/generate-api-types.mjs --check      # CI: fail when either file drifts
 *
 * `--check` is the CI gate (frontend/ARCHITECTURE.md §2): it re-fetches the
 * live spec and fails the build when the committed snapshot or the generated
 * types no longer match it, so a backend shape change can never land silently.
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SNAPSHOT = path.join(ROOT, 'openapi.json');
const TYPES = path.join(ROOT, 'src', 'lib', 'api-types.ts');

const DEFAULT_URL = process.env.OPENAPI_URL ?? 'http://localhost:8080/v3/api-docs';

const BANNER = `/**
 * GENERATED FILE — DO NOT EDIT.
 *
 * Cut from the backend's OpenAPI document (\`/v3/api-docs\`) by
 * \`npm run types:gen\`. \`npm run types:check\` fails CI when this file or
 * \`openapi.json\` drifts from the live backend — regenerate, never hand-patch.
 */

`;

/** The live document, pretty-printed the one way both the snapshot and the diff use. */
async function fetchSpec(url) {
  let response;
  try {
    response = await fetch(url);
  } catch (cause) {
    throw new Error(
      `Could not reach the OpenAPI document at ${url}. Start the backend ` +
        `(cd backend && ./mvnw spring-boot:run) or pass --offline to regenerate ` +
        `from the committed snapshot.`,
      { cause },
    );
  }
  if (!response.ok) throw new Error(`${url} answered ${response.status}`);
  return response.json();
}

function serializeSpec(spec) {
  return `${JSON.stringify(spec, null, 2)}\n`;
}

async function renderTypes(spec) {
  const ast = await openapiTS(spec, { alphabetize: true });
  return BANNER + astToString(ast);
}

async function readIfPresent(file) {
  try {
    return await fs.readFile(file, 'utf8');
  } catch {
    return null;
  }
}

async function main() {
  const args = process.argv.slice(2);
  const check = args.includes('--check');
  const offline = args.includes('--offline');
  const urlArg = args.find((a) => a.startsWith('--url='));
  const url = urlArg ? urlArg.slice('--url='.length) : DEFAULT_URL;

  const spec = offline
    ? JSON.parse(await fs.readFile(SNAPSHOT, 'utf8'))
    : await fetchSpec(url);

  const snapshot = serializeSpec(spec);
  const types = await renderTypes(spec);

  if (check) {
    const drift = [];
    if ((await readIfPresent(SNAPSHOT)) !== snapshot) drift.push('openapi.json');
    if ((await readIfPresent(TYPES)) !== types) drift.push('src/lib/api-types.ts');
    if (drift.length > 0) {
      console.error(
        `API type drift: ${drift.join(' and ')} no longer match ${url}.\n` +
          `Run \`npm run types:gen\` against the current backend and commit the result.`,
      );
      process.exit(1);
    }
    console.log(`API types are in sync with ${url}.`);
    return;
  }

  await fs.writeFile(SNAPSHOT, snapshot, 'utf8');
  await fs.writeFile(TYPES, types, 'utf8');
  const paths = Object.keys(spec.paths ?? {}).length;
  const schemas = Object.keys(spec.components?.schemas ?? {}).length;
  console.log(
    `Wrote openapi.json + src/lib/api-types.ts from ${offline ? SNAPSHOT : url} ` +
      `(${paths} paths, ${schemas} schemas).`,
  );
}

main().catch((error) => {
  console.error(error.message ?? error);
  process.exit(1);
});
