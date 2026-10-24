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
