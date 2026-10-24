import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { usePreferences } from '../preferences/PreferencesProvider'
import { t } from '../preferences/i18n'
import PixelIcon from './PixelIcon'
import { galleryIndex } from './gallery'
import './ImageGallery.css'

interface GalleryImage { src:string; alt:string; button:HTMLButtonElement }
interface GalleryState { images:GalleryImage[]; selected:HTMLButtonElement; origin:HTMLButtonElement }
interface GalleryContextValue {
  register:(button:HTMLButtonElement, image:Omit<GalleryImage,'button'> | null) => void
  open:(button:HTMLButtonElement) => void
}
const GalleryContext = createContext<GalleryContextValue | null>(null)
const PreviewOrderContext = createContext<readonly HTMLButtonElement[]>([])

/** Nested Markdown fragments inherit their chat message's gallery (e.g. daily previews). */
export function ImageGalleryProvider({ children }: { children:ReactNode }) {
  const inherited = useContext(GalleryContext)
  return inherited ? children : <GalleryScope>{children}</GalleryScope>
}

function GalleryScope({ children }: { children:ReactNode }) {
  const registry = useRef(new Map<HTMLButtonElement,GalleryImage>())
  const [gallery,setGallery] = useState<GalleryState | null>(null)
  const [order,setOrder] = useState<HTMLButtonElement[]>([])
  const images = useCallback(() => [...registry.current.values()].sort((a,b) => {
    if (a.button === b.button) return 0
    return a.button.compareDocumentPosition(b.button) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1
  }),[])
  const register = useCallback<GalleryContextValue['register']>((button,image) => {
    if (image) registry.current.set(button,{...image,button})
    else registry.current.delete(button)
    const nextOrder = images().map(image => image.button)
    setOrder(current => current.length === nextOrder.length && current.every((button,i) => button === nextOrder[i]) ? current : nextOrder)
    setGallery(current => {
      if (!current) return current
      const next = images()
      if (!next.length) return null
      const index = current.images.findIndex(image => image.button === current.selected)
      return {...current,images:next,selected:next.some(image => image.button === current.selected)
        ? current.selected : next[Math.min(Math.max(index,0),next.length-1)].button}
    })
  },[images])
  const open = useCallback((button:HTMLButtonElement) => {
    if (registry.current.has(button)) setGallery({images:images(),selected:button,origin:button})
  },[images])
  const close = useCallback(() => setGallery(null),[])
  const navigate = useCallback((step:number) => setGallery(current => {
    if (!current) return current
    const index = current.images.findIndex(image => image.button === current.selected)
    return {...current,selected:current.images[galleryIndex(index,step,current.images.length)].button}
  }),[])
  const context = useMemo(() => ({register,open}),[register,open])
  return <GalleryContext.Provider value={context}><PreviewOrderContext.Provider value={order}>
    {children}
    {gallery && <ImageViewer gallery={gallery} onClose={close} onNavigate={navigate} />}
  </PreviewOrderContext.Provider></GalleryContext.Provider>
}

export function GalleryThumbnail({ src, alt = '' }: { src?:string; alt?:string }) {
  usePreferences()
  const gallery = useContext(GalleryContext)
  const order = useContext(PreviewOrderContext)
  const [button,setButton] = useState<HTMLButtonElement | null>(null)
  const [failed,setFailed] = useState<string | null>(null)
  const attach = useCallback((button:HTMLButtonElement | null) => {
    if (!button || !src || !gallery) return
    setButton(button)
    gallery.register(button,{src,alt})
    return () => gallery.register(button,null)
  },[gallery,src,alt])
  const index = button ? order.indexOf(button) : -1
  const remaining = index === 1 ? Math.max(0,order.length - 2) : 0
  if (!src) return null
  const label = alt ? t('查看图片：{0}',{'0':alt}) : t('查看图片')
  return <button ref={attach} type="button" className="gallery-thumbnail" aria-haspopup="dialog" hidden={index > 1}
    aria-label={remaining ? `${label} · ${t('还有 {0} 张图片',{'0':remaining})}` : label}
    onClick={event => { event.preventDefault(); event.stopPropagation(); gallery?.open(event.currentTarget) }}>
    <img key={src} src={src} alt={alt} loading="lazy" referrerPolicy="no-referrer" hidden={failed === src}
      onError={() => setFailed(src)} onLoad={() => setFailed(null)} />
    {failed === src && <span className="gallery-thumbnail-error"><PixelIcon name="image" />{t('预览加载失败，点击重试')}</span>}
    {remaining > 0 && <span className="gallery-more" aria-hidden="true">+{remaining}</span>}
    <span className="gallery-thumbnail-caption">{alt || t('查看图片')}</span>
  </button>
}

function ImageDetail({ image }: { image:GalleryImage }) {
  const [status,setStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const [attempt,setAttempt] = useState(0)
  const imageRef = useRef<HTMLImageElement>(null)
  useEffect(() => {
    // Cached images may finish before React attaches its load listener (including StrictMode).
    const img = imageRef.current
    if (img?.complete) img.dispatchEvent(new Event(img.naturalWidth ? 'load' : 'error'))
  },[attempt])
  return <div className="gallery-stage" aria-busy={status === 'loading'}>
    <img key={attempt} ref={imageRef} className="gallery-full-image" src={image.src} alt={image.alt}
      referrerPolicy="no-referrer" hidden={status === 'error'} onLoad={() => setStatus('ready')} onError={() => setStatus('error')} />
    {status === 'loading' && <span className="gallery-loading" role="status">{t('图片加载中…')}</span>}
    {status === 'error' && <div className="gallery-error" role="status"><PixelIcon name="warning" />
      <p>{t('图片加载失败，请重试或切换其他图片。')}</p>
      <button type="button" onClick={() => { setStatus('loading');setAttempt(value => value+1) }}><PixelIcon name="refresh" />{t('重试')}</button>
    </div>}
  </div>
}

function ImageViewer({ gallery, onClose, onNavigate }: {
  gallery:GalleryState; onClose:() => void; onNavigate:(step:number) => void
}) {
  usePreferences()
  const dialogRef = useRef<HTMLDialogElement>(null)
  const index = gallery.images.findIndex(image => image.button === gallery.selected)
  const image = gallery.images[index]
  useEffect(() => {
    const dialog = dialogRef.current!
    const overflow = document.body.style.overflow
    dialog.showModal()
    document.body.style.overflow = 'hidden'
    return () => {
      dialog.close()
      document.body.style.overflow = overflow
      if (gallery.origin.isConnected) gallery.origin.focus({preventScroll:true})
    }
  },[gallery.origin])
  return createPortal(<dialog ref={dialogRef} className="gallery-viewer" aria-label={t('图片预览')}
    onCancel={event => { event.preventDefault();onClose() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const rect = event.currentTarget.getBoundingClientRect()
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onClose()
    }}
    onKeyDown={event => {
      // Keep Escape/Tab out of an underlying assessment drawer's keyboard handler.
      event.stopPropagation()
      if (event.key === 'Tab') {
        const buttons = [...event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')]
        const first = buttons[0], last = buttons.at(-1)
        if (event.shiftKey && document.activeElement === first) { event.preventDefault();last?.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault();first?.focus() }
      }
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault()
        if (gallery.images.length > 1) onNavigate(event.key === 'ArrowRight' ? 1 : -1)
      }
    }}>
    <header className="gallery-viewer-header">
      <h2>{image.alt || t('图片预览')}</h2>
      <button type="button" className="gallery-close" aria-label={t('关闭图片预览')} onClick={onClose}><PixelIcon name="close" /></button>
    </header>
    <ImageDetail key={`${index}:${image.src}`} image={image} />
    <footer className="gallery-controls">
      <button type="button" aria-label={t('上一张图片')} disabled={gallery.images.length < 2} onClick={() => onNavigate(-1)}><PixelIcon name="chevronLeft" /><span>{t('上一张')}</span></button>
      <span className="gallery-position" role="status" aria-live="polite">{t('第 {0} / {1} 张',{'0':index+1,'1':gallery.images.length})}</span>
      <button type="button" aria-label={t('下一张图片')} disabled={gallery.images.length < 2} onClick={() => onNavigate(1)}><span>{t('下一张')}</span><PixelIcon name="chevronRight" /></button>
    </footer>
  </dialog>,document.body)
}
