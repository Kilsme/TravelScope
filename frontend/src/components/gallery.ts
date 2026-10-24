/** Both ends wrap; a single image stays selected and an empty gallery has no index. */
export function galleryIndex(index:number, step:number, count:number): number {
  return count > 0 ? ((index + step) % count + count) % count : 0
}
