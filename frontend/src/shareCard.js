// "Ticket Stub" share card: a die-cut ticket with a torn bottom edge, a
// rubber-stamped grade, and dot-leader line items — chosen over 4 other
// directions after a design review. Exported as PNG (not JPEG) specifically
// so the torn edge and the two side notches are real transparent cutouts
// that read correctly against any chat background, not just a fixed color.
const WIDTH = 1080
const HEIGHT = 1350
const MARGIN_X = 86
const INK = '#2b2a28'
const PAPER = '#f2ead8'
const STAMP_RED = '#b23b3b'
const FONT = 'ui-monospace, "SFMono-Regular", Consolas, "Liberation Mono", monospace'

async function loadImage(source) {
  if (!source) return null
  const isBlob = source instanceof Blob
  const url = isBlob ? URL.createObjectURL(source) : source
  try {
    const img = new Image()
    img.src = url
    await img.decode()
    return img
  } catch {
    return null
  } finally {
    if (isBlob) URL.revokeObjectURL(url)
  }
}

function drawCover(ctx, img, x, y, w, h) {
  const scale = Math.max(w / img.width, h / img.height)
  const sw = w / scale
  const sh = h / scale
  const sx = (img.width - sw) / 2
  const sy = (img.height - sh) / 2
  ctx.drawImage(img, sx, sy, sw, sh, x, y, w, h)
}

// Outer ticket outline (top+sides square, bottom torn) followed by the two
// edge-notch circles, all added to the CURRENT path so a caller can
// ctx.clip('evenodd') it — the even-odd rule punches the circles and
// everything past the torn edge out to transparent.
function ticketClipPath(ctx) {
  const depth = HEIGHT * 0.035
  const topY = HEIGHT - depth
  const teeth = 7
  const n = teeth * 2
  const step = WIDTH / n
  ctx.moveTo(0, 0)
  ctx.lineTo(WIDTH, 0)
  ctx.lineTo(WIDTH, topY)
  for (let i = 1; i <= n; i++) {
    const x = WIDTH - step * i
    const y = i % 2 === 1 ? HEIGHT : topY
    ctx.lineTo(x, y)
  }
  ctx.closePath()

  const notchY = HEIGHT * 0.46
  const notchR = 30
  ctx.moveTo(notchR, notchY)
  ctx.arc(0, notchY, notchR, 0, Math.PI * 2)
  ctx.moveTo(WIDTH, notchY + notchR)
  ctx.arc(WIDTH, notchY, notchR, 0, Math.PI * 2)
}

function truncateToWidth(ctx, text, maxWidth) {
  if (ctx.measureText(text).width <= maxWidth) return text
  let lo = 0
  let hi = text.length
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2)
    const candidate = text.slice(0, mid) + '…'
    if (ctx.measureText(candidate).width <= maxWidth) lo = mid
    else hi = mid - 1
  }
  return text.slice(0, lo) + '…'
}

function wrapToLines(ctx, text, maxWidth, maxLines) {
  const words = text.split(' ')
  const lines = []
  let current = ''
  for (const word of words) {
    const attempt = current ? `${current} ${word}` : word
    if (ctx.measureText(attempt).width <= maxWidth) {
      current = attempt
    } else {
      if (current) lines.push(current)
      current = word
    }
    if (lines.length === maxLines - 1 && current !== attempt) break
  }
  if (current) lines.push(current)
  if (lines.length > maxLines) lines.length = maxLines
  const last = lines.length - 1
  if (last >= 0) lines[last] = truncateToWidth(ctx, lines[last], maxWidth)
  return lines
}

function drawLeaderRow(ctx, label, value, y, left, right, labelColour) {
  ctx.textBaseline = 'alphabetic'
  // The label may be CJK (the menu tier names), which the monospace stack has
  // no glyphs for — measure and draw it in whichever font is about to render
  // it, or the dot leader starts in the wrong place.
  ctx.font = labelColour ? `700 39px ${LABEL_FONT}` : `700 39px ${FONT}`
  ctx.textAlign = 'left'
  ctx.fillStyle = labelColour ?? INK
  ctx.fillText(label, left, y)
  const labelEnd = left + ctx.measureText(label).width

  ctx.font = `700 39px ${FONT}`
  ctx.fillStyle = INK
  ctx.textAlign = 'right'
  ctx.fillText(value, right, y)
  const valueStart = right - ctx.measureText(value).width

  const dotY = y - 12
  ctx.strokeStyle = 'rgba(43, 42, 40, 0.5)'
  ctx.lineWidth = 3
  ctx.setLineDash([4, 8])
  ctx.beginPath()
  ctx.moveTo(labelEnd + 14, dotY)
  ctx.lineTo(valueStart - 14, dotY)
  ctx.stroke()
  ctx.setLineDash([])
}

function drawBarcode(ctx, x, y, w, h) {
  // Repeats every 44px: bar[0-7] gap[7-10] bar[10-17] gap[17-27] bar[27-30] gap[30-44].
  const unit = 44
  const bars = [
    [0, 7],
    [10, 17],
    [27, 30],
  ]
  ctx.fillStyle = INK
  for (let ux = 0; ux < w; ux += unit) {
    for (const [start, end] of bars) {
      const bx = x + ux + start
      if (bx >= x + w) continue
      const bw = Math.min(end, w - ux) - start
      if (bw > 0) ctx.fillRect(bx, y, bw, h)
    }
  }
}

/**
 * Renders the "Ticket Stub" share card as a PNG Blob: a die-cut ticket with
 * a torn bottom edge, a rotated ink-stamp grade, dot-leader nutrition line
 * items, a highlight note, and a decorative barcode + tracking number.
 */

// The tier labels are Chinese (see tierMeta.js) and FONT is a monospace stack —
// Consolas and Liberation Mono carry no CJK glyphs. Canvas silently falls back
// to whatever the system has, unlike the PDF exporter which just drops them
// (outstanding item 6), but relying on that fallback for the one thing the menu
// card is about would be careless. Labels get a stack that actually has CJK.
const LABEL_FONT = 'system-ui, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif'

/** Paper, clip, brand header and the rule under it — shared by both cards. */
function drawTicketChrome(ctx, brandTitle, ticketNumber) {
  ctx.save()
  ctx.beginPath()
  ticketClipPath(ctx)
  ctx.clip('evenodd')

  ctx.fillStyle = PAPER
  ctx.fillRect(0, 0, WIDTH, HEIGHT)

  ctx.textBaseline = 'alphabetic'
  ctx.fillStyle = INK
  ctx.font = `700 37px ${FONT}`
  ctx.textAlign = 'left'
  ctx.fillText(brandTitle.toUpperCase(), MARGIN_X, 81)
  ctx.textAlign = 'right'
  ctx.fillText(ticketNumber, WIDTH - MARGIN_X, 81)

  dashedRule(ctx, 108)
}

function dashedRule(ctx, y) {
  ctx.strokeStyle = 'rgba(43, 42, 40, 0.35)'
  ctx.lineWidth = 3
  ctx.setLineDash([5, 8])
  ctx.beginPath()
  ctx.moveTo(MARGIN_X, y)
  ctx.lineTo(WIDTH - MARGIN_X, y)
  ctx.stroke()
  ctx.setLineDash([])
}

/** The dashed-frame photo square, with an emoji tile when there is no image. */
function drawPhotoSquare(ctx, img, fallbackEmoji, top, size) {
  ctx.save()
  ctx.strokeStyle = 'rgba(43, 42, 40, 0.4)'
  ctx.lineWidth = 7
  ctx.setLineDash([10, 8])
  ctx.strokeRect(MARGIN_X, top, size, size)
  ctx.setLineDash([])
  if (img) {
    ctx.filter = 'sepia(0.2) contrast(1.05)'
    drawCover(ctx, img, MARGIN_X, top, size, size)
    ctx.filter = 'none'
  } else {
    ctx.fillStyle = '#e8ddc8'
    ctx.fillRect(MARGIN_X, top, size, size)
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.font = '160px system-ui, sans-serif'
    ctx.fillText(fallbackEmoji, MARGIN_X + size / 2, top + size / 2)
  }
  ctx.restore()
}

/** Barcode strip and serial line at the foot of the ticket. */
function drawTicketFooter(ctx, serial) {
  drawBarcode(ctx, MARGIN_X, 1073, WIDTH - MARGIN_X * 2, 115)
  ctx.textAlign = 'left'
  ctx.textBaseline = 'alphabetic'
  ctx.fillStyle = INK
  ctx.font = `600 34px ${FONT}`
  ctx.fillText(serial, MARGIN_X, 1256)
}

function today() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
}

export async function buildShareCard({ result, imageSource, brandTitle, shareText, barcodeLabel }) {
  const { grade, score, totals, highlights, source } = result

  const canvas = document.createElement('canvas')
  canvas.width = WIDTH
  canvas.height = HEIGHT
  const ctx = canvas.getContext('2d')

  drawTicketChrome(ctx, brandTitle, `NO. ${String(Math.round(score)).padStart(4, '0')}`)

  // Photo (or a fallback tile when there's none — barcode results never have one).
  const photoTop = 216
  const photoSize = 367
  const img = await loadImage(imageSource)
  drawPhotoSquare(ctx, img, source === 'barcode' ? '🔖' : '🍽️', photoTop, photoSize)

  // Rotated ink stamp overlapping the photo's bottom-right corner.
  const stampSize = 238
  const stampCx = MARGIN_X + 184 + stampSize / 2
  const stampCy = 446 + stampSize / 2
  ctx.save()
  ctx.globalAlpha = 0.85
  ctx.globalCompositeOperation = 'multiply'
  ctx.translate(stampCx, stampCy)
  ctx.rotate((-14 * Math.PI) / 180)
  ctx.beginPath()
  ctx.arc(0, 0, stampSize / 2, 0, Math.PI * 2)
  ctx.lineWidth = 10
  ctx.strokeStyle = STAMP_RED
  ctx.stroke()
  ctx.fillStyle = STAMP_RED
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.font = `700 51px ${FONT}`
  ctx.fillText(grade, 0, -8)
  ctx.font = `700 22px ${FONT}`
  ctx.fillText('GRADE', 0, 34)
  ctx.restore()

  // Dot-leader nutrition line items, right of the photo.
  const linesRight = WIDTH - MARGIN_X
  const linesLeft = linesRight - 497
  const rows = [
    ['SCORE', `${Math.round(score)}/100`],
    ['CAL', `${Math.round(totals.calories)}`],
    ['PROT', `${Math.round(totals.protein)}g`],
    ['CARB', `${Math.round(totals.carbs)}g`],
    ['FAT', `${Math.round(totals.fat)}g`],
  ]
  rows.forEach(([label, value], i) => {
    drawLeaderRow(ctx, label, value, 260 + i * 130, linesLeft, linesRight)
  })

  let noteBottom = 864
  if (source === 'barcode' && barcodeLabel) {
    ctx.textAlign = 'left'
    ctx.fillStyle = STAMP_RED
    ctx.font = `700 30px ${FONT}`
    ctx.fillText(`🔖 ${barcodeLabel}`, linesLeft, 800)
  }

  // Highlight note, up to two lines, with its own dashed rule above it.
  if (highlights?.[0]) {
    dashedRule(ctx, noteBottom - 27)

    ctx.textAlign = 'left'
    ctx.textBaseline = 'alphabetic'
    ctx.fillStyle = INK
    ctx.font = `500 35px ${FONT}`
    const lines = wrapToLines(ctx, `✓ ${highlights[0]}`, WIDTH - MARGIN_X * 2, 2)
    lines.forEach((line, i) => ctx.fillText(line, MARGIN_X, noteBottom + i * 46))
  }

  drawTicketFooter(ctx, `${Math.round(score)}${grade.replace('+', '')}-${today()}`)

  ctx.restore() // lift the ticket clip

  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'))
  return { blob, shareText }
}

export function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/**
 * The menu-scan equivalent: the same ticket, ranking dishes instead of grading
 * a plate.
 *
 * A menu result has no score, no grade and no totals, so none of the meal
 * card's body applies — what it has is five tiers of dishes. The chrome is
 * shared (drawTicketChrome / drawPhotoSquare / drawTicketFooter) so the two
 * cards cannot drift into looking like they came from different apps.
 *
 * @param tierRows [{ label, colour, count, dishes: string[] }], best tier first
 */
export async function buildMenuShareCard({
  tierRows, dishCount, imageSource, brandTitle, shareText, dishCountLabel,
}) {
  const canvas = document.createElement('canvas')
  canvas.width = WIDTH
  canvas.height = HEIGHT
  const ctx = canvas.getContext('2d')

  drawTicketChrome(ctx, brandTitle, `NO. ${String(dishCount).padStart(4, '0')}`)

  const photoTop = 216
  const photoSize = 367
  const img = await loadImage(imageSource)
  drawPhotoSquare(ctx, img, '📋', photoTop, photoSize)

  // One leader row per tier, right of the photo — the same five-row rhythm the
  // meal card uses for its nutrition lines, so both read as the same object.
  const linesRight = WIDTH - MARGIN_X
  const linesLeft = linesRight - 497
  tierRows.slice(0, 5).forEach((row, i) => {
    drawLeaderRow(ctx, row.label, String(row.count), 260 + i * 130, linesLeft, linesRight, row.colour)
  })

  const noteTop = 864
  dashedRule(ctx, noteTop - 27)

  ctx.textAlign = 'left'
  ctx.textBaseline = 'alphabetic'
  ctx.fillStyle = INK
  ctx.font = `500 32px ${FONT}`
  ctx.fillText(dishCountLabel.toUpperCase(), MARGIN_X, noteTop)

  // Name the dishes in the best non-empty tier. A tier list with no dishes on it
  // is just five numbers, and the names are what a friend actually reads.
  const best = tierRows.find((row) => row.dishes.length > 0)
  if (best) {
    ctx.font = `700 40px ${LABEL_FONT}`
    ctx.fillStyle = best.colour
    ctx.fillText(best.label, MARGIN_X, noteTop + 62)

    ctx.fillStyle = INK
    ctx.font = `500 33px ${FONT}`
    best.dishes.slice(0, 3).forEach((name, i) => {
      ctx.fillText(truncateToWidth(ctx, `- ${name}`, WIDTH - MARGIN_X * 2), MARGIN_X, noteTop + 116 + i * 44)
    })
  }

  drawTicketFooter(ctx, `MENU${String(dishCount).padStart(2, '0')}-${today()}`)

  ctx.restore()

  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'))
  return { blob, shareText }
}
