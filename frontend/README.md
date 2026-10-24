# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.

## Chat typewriter output

The user chat page uses `TypewriterMessage` / `useTypewriter` to buffer **display only**. Durable snapshots, IndexedDB content, SSE sequencing, cancellation and server task status remain authoritative and independent from animation.

- Prose reveals one grapheme cluster per animation step, with a minimum 60 ms between characters. Commas pause for 120 ms and sentence endings for 220 ms. Neither large bursts, slow frames nor successful completion accelerate or release multiple prose characters together.
- GFM tables, code and images are revealed atomically. Potential unfinished table headers and image URLs wait for closing source or completion. The final Markdown still goes through the shared `MarkdownView`.
- Existing history / restored snapshots render immediately. Non-prefix final rewrites, failure, cancellation, explicit Stop, reduced-motion preferences and hidden tabs flush to received source immediately. Existing per-day itinerary previews are not replayed as a final animation.
- A successful server completion can enable the next input while the previous display finishes. The pixel cursor is inserted inside the final Markdown text container so it follows the last character, including headings, lists and inline formatting, independently of persisted task status.
- `useChatScroll` follows content/image growth only while within 72 px of the bottom. Scrolling up pauses following; scrolling back down, sending, or selecting a conversation resumes it.

Run `npm test`, `npm run build`, and `npm run lint` in `frontend`. The dedicated browser acceptance script intercepts APIs and uses an isolated fictitious account. With Vite running on `127.0.0.1:5173`, run from the repository root:

```powershell
New-Item -ItemType Directory -Force output/typewriter
npm exec --yes --package=@playwright/cli -- playwright-cli -s=typewriter open http://127.0.0.1:5173/
npm exec --yes --package=@playwright/cli -- playwright-cli -s=typewriter --raw run-code --filename=frontend/tests/typewriter.browser.js
npm exec --yes --package=@playwright/cli -- playwright-cli -s=typewriter close
```

The fixture covers gradual output, atomic Markdown, recovery, final replacement, cache integrity, explicit stop before acknowledgment, failures, reduced motion, themes and scroll position. Screenshots are saved under `output/typewriter/` (ignored by Git).

## Message copy and quoted replies

Each message has a persistent pixel toolbar below its bubble: user actions align right and assistant actions align left, above the activity stream. Empty/image-only messages have disabled actions. An assistant's actions wait for both server completion and the existing typewriter buffer; saved stopped/failed prose remains actionable. During a live task, completed history can still be copied, while Reply follows the composer's existing lock.

Copy writes the original message text, including Markdown, with no cursor, cards or activity text. It uses the Clipboard API and a selection/focus-preserving textarea fallback. Copy status is local to the toolbar. Windows may normalize clipboard line endings to CRLF.

Reply keeps one removable quote above the composer without replacing the draft or pending photo. Preview expansion displays plain text, including the complete original. The outgoing excerpt is limited to 2000 UTF-16 units at a grapheme boundary, matching the server's string-length convention; an explicit excerpt label is included when truncated. At send time, localized quote/question labels and `>`-prefixed quote lines are combined into the existing `message` field, with an 8000-unit total limit. `sourceTurnId` remains reserved for requirement forms. The sent history is ordinary combined text, not a new structured message format.

The quote alone cannot be sent. Local rejection preserves the editor's quote/text/photo rather than restoring a second serialized copy. Reload recovery can restore the existing Outbox prompt as ordinary text. Acceptance only clears an unchanged draft in the original conversation; navigation cancels the unsent quote. No new backend API, database field or quote persistence is introduced.

The browser fixture verifies native copy and Ctrl+V, compatibility copy/selection restoration and failures, quote replacement/cancellation/expansion, length limits, image retries, draft races, live/typewriter locks, four themes, English, narrow layout and history reload. Run from the repository root with Vite on `127.0.0.1:5173`:

```powershell
New-Item -ItemType Directory -Force output/message-actions
npm exec --yes --package=@playwright/cli -- playwright-cli -s=message-actions open http://127.0.0.1:5173/
npm exec --yes --package=@playwright/cli -- playwright-cli -s=message-actions --raw run-code --filename=frontend/tests/message-actions.browser.js
npm exec --yes --package=@playwright/cli -- playwright-cli -s=message-actions close
```
