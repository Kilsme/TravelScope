import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ImageGalleryProvider } from '../src/components/ImageGallery'
import MarkdownView from '../src/components/MarkdownView'
import { Overlay } from '../src/pages/admin/assessment/common'
import '../src/pages/admin/AssessmentPage.css'

let cleanup:(() => void) | undefined
export function mountNestedGallery(urls:string[]) {
  cleanup?.()
  const target = document.createElement('div')
  document.body.append(target)
  const root = createRoot(target)
  function Fixture() {
    const [open,setOpen] = useState(true)
    return <ImageGalleryProvider>{open && <Overlay title="图集嵌套验收" onClose={() => setOpen(false)}>
      <article className="as-markdown"><MarkdownView text={`第一段\n\n![嵌套图片一](${urls[0]})`} /></article>
      <article className="as-markdown"><MarkdownView text={`第二段\n\n![嵌套图片二](${urls[1]})`} /></article>
    </Overlay>}</ImageGalleryProvider>
  }
  root.render(<Fixture />)
  cleanup = () => { root.unmount();target.remove() }
}
export function unmountNestedGallery() { cleanup?.();cleanup = undefined }
