import type { SVGProps } from 'react'

// Every glyph is drawn on the same 16px integer grid, with filled square steps.
const glyphs = {
  plus: 'M7 2h2v5h5v2H9v5H7V9H2V7h5Z',
  close: 'M3 2h2v2h2v2h2V4h2V2h2v2h-2v2H9v4h2v2h2v2h-2v-2H9v-2H7v2H5v2H3v-2h2v-2h2V6H5V4H3Z',
  check: 'M1 7h2v2h2v2h2V9h2V7h2V5h2V3h2v4h-2v2h-2v2H9v2H5v-2H3V9H1Z',
  warning: 'M7 1h2v2h2v4h2v4h2v4H1v-4h2V7h2V3h2Zm0 4v5h2V5Zm0 7v2h2v-2Z',
  chat: 'M2 2h12v2h2v8h-2v2H6v2H2v-4H0V4h2Zm0 2v8h4v-2h8V4Z',
  image: 'M1 2h14v12H1Zm2 2v8h2v-2h2V8h2v2h2v2h2V4Zm1 1h2v2H4Z',
  send: 'M1 1h2v2h4v2h4v2h4v2h-4v2H7v2H3v2H1V9h6V7H1Zm2 4v2h4V5Zm0 4v2h4V9Z',
  stop: 'M3 3h10v10H3Z',
  sun: 'M7 0h2v3H7Zm0 13h2v3H7ZM0 7h3v2H0Zm13 0h3v2h-3ZM2 2h2v2H2Zm10 0h2v2h-2ZM2 12h2v2H2Zm10 0h2v2h-2ZM5 4h6v2h2v4h-2v2H5v-2H3V6h2Z',
  moon: 'M7 1h3v2H8v5h2v2h5v3h-2v2H5v-2H3v-2H1V5h2V3h4Zm-2 4H3v6h2v2h6v-1H8v-2H6V5Z',
  palette: 'M4 1h8v2h2v2h2v6h-2v2h-4v2H4v-2H2v-2H0V5h2V3h2Zm0 4v2h2V5Zm4-2v2h2V3Zm4 3v2h2V6ZM3 9v2h2V9Zm5 1v3h2v-3Z',
  settings: 'M6 0h4v3h2V2h2v2h-1v2h3v4h-3v2h1v2h-2v-1h-2v3H6v-3H4v1H2v-2h1v-2H0V6h3V4H2V2h2v1h2Zm0 5v1H5v4h1v1h4v-1h1V6h-1V5Z',
  language: 'M0 2h4V0h2v2h4v2H8v4H6v2h2v2H6v-2H4v2H0v-2h4V8H2V6h2v2h2V4H0Zm10 5h2v2h2v2h2v5h-2v-2h-4v2H8v-5h2Zm0 4v1h4v-1h-2V9h-2Z',
  users: 'M4 1h5v2h2v5H9v2H4V8H2V3h2Zm0 2v5h5V3ZM2 11h9v2h2v3H0v-3h2Zm10-8h2v2h2v4h-2v2h-2V9h2V5h-2Zm2 9h2v4h-2Z',
  chart: 'M1 1h2v12h12v2H1Zm4 6h2v4H5Zm4-3h2v7H9Zm4-3h2v10h-2Z',
  document: 'M2 0h8v2h2v2h2v12H2Zm2 2v12h8V6H8V2Zm1 5h5v2H5Zm0 4h5v1H5Z',
  ticket: 'M0 3h16v4h-2v2h2v4H0V9h2V7H0Zm2 2v1h2v4H2v1h8V9h2v2h2v-1h-2V6h2V5h-2v2h-2V5Z',
  flask: 'M4 0h8v2h-2v4h2v2h2v2h2v6H0v-6h2V8h2V6h2V2H4Zm2 8v2H4v2H2v2h12v-2h-2v-2h-2V8H8V2H8v6Z',
  map: 'M0 3h2V1h4v2h4V1h4v2h2v12h-2v1h-4v-2H6v2H2v-2H0Zm2 2v8h2V3H2Zm4 0v7h4V5Zm6-2v10h2V3Z',
  book: 'M0 2h6v1h4V2h6v12h-6v2H6v-2H0Zm2 2v8h4v1h1V5H6V4Zm8 1H9v8h1v-1h4V4h-4Z',
  help: 'M4 0h8v2h2v4h-2v2h-2v3H7V7h3V5h2V3H4v3H2V2h2Zm3 13h3v3H7Z',
  globe: 'M4 0h8v2h2v2h2v8h-2v2h-2v2H4v-2H2v-2H0V4h2V2h2Zm2 2v4h4V2Zm-2 2H2v2h2Zm8 0v2h2V4ZM2 8v2h2V8Zm4 0v2h4V8Zm6 0v2h2V8Zm-6 4v2h4v-2Z',
  clipboard: 'M5 0h6v2h3v14H2V2h3Zm0 4H4v10h8V4h-1v2H5Zm1 4h4v2H6Zm0 3h4v1H6Z',
  refresh: 'M4 1h8v2h2V1h2v6h-6V5h2V3H4v2H2v6h2v2h8v-2h2v2h-2v2H4v-2H2v-2H0V5h2V3h2Z',
  chevronRight: 'M5 2h2v2h2v2h2v4H9v2H7v2H5v-2h2v-2h2V6H7V4H5Z',
  chevronDown: 'M2 5h2v2h2v2h4V7h2V5h2v2h-2v2h-2v2H6V9H4V7H2Z',
  clock: 'M4 0h8v2h2v2h2v8h-2v2h-2v2H4v-2H2v-2H0V4h2V2h2Zm0 2v2H2v8h2v2h8v-2h2V4h-2V2Zm3 2h2v4h3v2H7Z',
} as const

export type PixelIconName = keyof typeof glyphs
export default function PixelIcon({ name, className = '', ...props }: SVGProps<SVGSVGElement> & { name: PixelIconName }) {
  return <svg viewBox="0 0 16 16" width="16" height="16" fill="currentColor" aria-hidden="true"
    className={`pixel-icon ${className}`} shapeRendering="crispEdges" {...props}><path d={glyphs[name]} /></svg>
}
