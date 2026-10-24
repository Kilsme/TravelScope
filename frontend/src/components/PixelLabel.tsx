import PixelIcon from './PixelIcon'
import type { PixelIconName } from './PixelIcon'

const prefixes: [string, PixelIconName][] = [
  ['💬', 'chat'], ['🔧', 'settings'], ['🗺️', 'map'], ['🗺', 'map'], ['📚', 'book'],
  ['❓', 'help'], ['⚙️', 'settings'], ['🌍', 'globe'], ['📋', 'clipboard'], ['⏳', 'clock'],
  ['✅', 'check'], ['❌', 'close'], ['⚠️', 'warning'], ['💡', 'help'],
  ['🧪', 'flask'], ['📊', 'chart'], ['👥', 'users'], ['📄', 'document'], ['🎫', 'ticket'],
  ['＋', 'plus'], ['↻', 'refresh'],
]
/** Only application-owned labels use this component. Never pass message bodies. */
export default function PixelLabel({ text }: { text: string }) {
  const match = prefixes.find(([prefix]) => text.startsWith(prefix))
  return match ? <><PixelIcon name={match[1]} />{' '}{text.slice(match[0].length).trimStart()}</> : <>{text}</>
}
