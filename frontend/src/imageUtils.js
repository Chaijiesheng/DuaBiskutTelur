const MAX_EDGE = 1568
const JPEG_QUALITY = 0.8

/**
 * Downscale/compress a photo client-side before upload so requests stay fast
 * on mobile data: max 1568px on the long edge, JPEG ~0.8 quality.
 */
export async function compressImage(file) {
  const bitmap = await createImageBitmap(file).catch(() => null)
  if (!bitmap) {
    // Unsupported format for canvas — send as-is and let the backend decide
    return file
  }
  const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height))
  const width = Math.round(bitmap.width * scale)
  const height = Math.round(bitmap.height * scale)

  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  canvas.getContext('2d').drawImage(bitmap, 0, 0, width, height)
  bitmap.close()

  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY))
  return blob ?? file
}
