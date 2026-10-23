# Theme and response language

The chat sidebar footer provides disabled Settings, a light/dark toggle, and a Chinese/English toggle. Appearance preferences apply across chat, authentication and administration. Defaults are light and `zh-CN`.

Preferences live in browser storage under `travelscope:appearance:v1`, remain after logout and synchronize across tabs. Storage failures fall back to an in-memory preference. The HTML bootstrap sets the root theme before rendering. Global semantic CSS variables provide both palettes; the React preferences provider refreshes interface text without remounting chat or restarting requests.

The typed message catalog covers application-owned captions and known service notices. Unknown diagnostics, model output, conversation titles, user data and raw JSON remain verbatim. Known notices store a key and parameters. Form caption metadata is additive; translated option captions never change submitted values.

## Deployment

1. Back up PostgreSQL and the backend JAR; wait for chat turns, batches and scoring to finish.
2. Apply `src/main/resources/chat-locale-upgrade.sql`. It is safe to repeat; initialization and reconnect upgrades also include these additions.
3. Deploy the backend, then the frontend. Keep the new database fields on rollback.

`POST /api/chat/turns` accepts optional `locale: "zh-CN" | "en"`, defaulting to Chinese. Other locales are rejected. Task responses include `locale` and `responseLocale`.

Each outbox entry captures its language when created. Retries and reconnects preserve that request even when the interface language changes. New requests use hash version 2 including locale; historical version 1 hashes remain unchanged and accept original Chinese/default retries. Changing a task's original locale causes an idempotency conflict. No account preference API is added.

Response language is carried explicitly through the runner, agent runtime, image prompt, planning/revision and parallel copy requests. Explicit Chinese/English language instructions override the interface default; otherwise the captured locale is used. Structured itinerary contracts and verified place names remain canonical. Code renders day previews, final tables and cached-result summaries in the same task language, without adding a translation model call. Legacy/evaluation callers default to Chinese.

## Validation

Run frontend tests and production build, and backend language, reconnect, PostgreSQL, image, planning, formatter, review and Trace tests. PostgreSQL suites require explicitly configured isolated test database variables. They never use the business database.

With the Vite development server running, `/tests/preferences.html` is a standalone browser fixture for long sidebar lists, editing requirement fields and switching preferences. It uses fixed data and makes no API/model calls. It is outside application routes and is not an entry point in the production build.
