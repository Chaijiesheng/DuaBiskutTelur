const MAX_EDGE = 1568
const JPEG_QUALITY = 0.8

// Menus need more headroom than a plated meal: small printed/handwritten text
// (especially CJK characters) needs more pixels per glyph to stay legible
// after JPEG compression than recognizing food shapes/colors does. Garbled
// text here means missed or misread dishes, not just a slightly fuzzier photo.
const MENU_MAX_EDGE = 2400
const MENU_JPEG_QUALITY = 0.9

async function compress(file, maxEdge, quality) {
  const bitmap = await createImageBitmap(file).catch(() => null)
  if (!bitmap) {
    // Unsupported format for canvas — send as-is and let the backend decide
    return file
  }
  const scale = Math.min(1, maxEdge / Math.max(bitmap.width, bitmap.height))
  const width = Math.round(bitmap.width * scale)
  const height = Math.round(bitmap.height * scale)

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  canvas.getContext('2d').drawImage(bitmap, 0, 0, width, height)
  bitmap.close()

  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
  return blob ?? file
}

/**
 * Downscale/compress a photo client-side before upload so requests stay fast
 * on mobile data: max 1568px on the long edge, JPEG ~0.8 quality.
 */
export async function compressImage(file) {
  return compress(file, MAX_EDGE, JPEG_QUALITY)
}

/** Same idea as compressImage, but tuned for reading menu text rather than recognizing plated food. */
export async function compressMenuImage(file) {
  return compress(file, MENU_MAX_EDGE, MENU_JPEG_QUALITY)
}
