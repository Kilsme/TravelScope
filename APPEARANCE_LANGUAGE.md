# Theme and response language

The chat sidebar footer provides disabled Settings, a four-theme picker, and a Chinese/English toggle. Administration provides theme and language controls without disabled Settings. All pages share one pixel design system. Presets are `light` (glacier blue), `dark` (electric violet), `peach` (水蜜桃) and `blue-bowl` (蓝白碗). Defaults are light and `zh-CN`.

Preferences live in browser storage under `travelscope:appearance:v2:user:<userId>`. Each account owns its theme and language. Logout and current-session 401 detach the owner and reset anonymous appearance; login restores that account. Tabs synchronize the active owner's record. No backend preference API or cross-device sync is added. Unowned legacy v1 records are not copied to accounts. Storage failures fall back to per-account memory. The HTML bootstrap applies the cached account theme before rendering. Four CSS variable blocks change colors only; pixel geometry, font and icons are shared. Preference updates do not remount chat or restart requests.

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

## Pixel UI assets and verification

The local Fusion Pixel 12px proportional Simplified Chinese WOFF2 is pinned to release `2026.10.16`: https://github.com/TakWolf/fusion-pixel-font/releases/tag/2026.10.16 . Its OFL and upstream notices are distributed beside it in `frontend/public/fonts/`. UI text uses Fusion Pixel with system-font fallbacks; code and raw JSON use a conventional monospace. `PixelIcon` uses filled integer SVG geometry and currentColor. `PixelLabel` only replaces prefixes of application-owned labels; message bodies and business data are preserved.

`AdminPrimitives` supplies the shared management header, panel and table wrapper. Assessment retains its specialized tabs, drawers, trees, time axis and data hooks. All five admin routes use the shared hierarchy. No pagination API or business feature was added.

Run `npm test`, `npm run build` and `npm run lint` in `frontend`. The browser acceptance script uses intercepted APIs and fictitious identities. Start Vite on `127.0.0.1:5173`, create `output/pixel-ui`, then run from the repository root:

```powershell
npm exec --yes --package=@playwright/cli -- playwright-cli -s=pixel-ui open http://127.0.0.1:5173/
npm exec --yes --package=@playwright/cli -- playwright-cli -s=pixel-ui --raw run-code --filename=frontend/tests/pixel-ui.browser.js
npm exec --yes --package=@playwright/cli -- playwright-cli -s=pixel-ui close
```

Screenshots go to `output/pixel-ui/` (ignored by Git). Checks cover four themes on chat and all management pages, draft/form/image retention, English, keyboard interaction, active SSE retention, account isolation, logout, cross-tab updates, Trace and narrow viewports. Use a dedicated CLI browser session for this fixture.
