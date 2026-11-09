// 이미지 첨부 준비: 브라우저에서 먼저 줄이고 JPEG로 다시 그려서 보낸다.
// (EXIF 위치·기기 정보가 서버로 나가지 않고, 전송량도 줄어든다. 서버도 같은 검증을 다시 한다.)

const ACCEPTED = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
const MAX_INPUT_BYTES = 12 * 1024 * 1024
const MAX_SIDE = 512 // 감정 분석용이라 작게 줄여 보낸다
const MAX_OUTPUT_BYTES = 2.5 * 1024 * 1024
const THUMB_SIDE = 160

export const IMAGE_ACCEPT = ACCEPTED.join(',')

function loadBitmap(file) {
  if (typeof createImageBitmap === 'function') {
    return createImageBitmap(file).catch(() => loadWithImageElement(file))
  }
  return loadWithImageElement(file)
}

function loadWithImageElement(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      resolve(img)
    }
    img.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('이미지를 읽을 수 없습니다.'))
    }
    img.src = url
  })
}

function drawScaled(source, maxSide) {
  const w = source.width
  const h = source.height
  const ratio = Math.min(1, maxSide / Math.max(w, h))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(w * ratio))
  canvas.height = Math.max(1, Math.round(h * ratio))
  const ctx = canvas.getContext('2d')
  ctx.fillStyle = '#ffffff' // 투명 배경은 흰색으로
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height)
  return canvas
}

/** File → { base64, mime, previewUrl, name }. 형식·크기가 맞지 않으면 사용자에게 보여줄 메시지로 throw. */
export async function prepareImage(file) {
  if (!file || !ACCEPTED.includes(file.type)) {
    throw new Error('JPG, PNG, GIF, WEBP 이미지만 올릴 수 있습니다.')
  }
  if (file.size > MAX_INPUT_BYTES) {
    throw new Error('이미지가 너무 큽니다. 12MB 이하로 올려주세요.')
  }
  const bitmap = await loadBitmap(file).catch(() => {
    throw new Error('이미지를 읽을 수 없습니다.')
  })
  try {
    const canvas = drawScaled(bitmap, MAX_SIDE)
    let base64 = null
    for (const quality of [0.85, 0.7, 0.55]) {
      const dataUrl = canvas.toDataURL('image/jpeg', quality)
      const candidate = dataUrl.slice(dataUrl.indexOf(',') + 1)
      if (candidate.length * 0.75 <= MAX_OUTPUT_BYTES) {
        base64 = candidate
        break
      }
    }
    if (!base64) throw new Error('이미지가 너무 큽니다. 더 작은 이미지를 올려주세요.')
    const thumb = drawScaled(bitmap, THUMB_SIDE).toDataURL('image/jpeg', 0.7)
    return { base64, mime: 'image/jpeg', previewUrl: thumb, name: file.name || '이미지' }
  } finally {
    if (typeof bitmap.close === 'function') bitmap.close()
  }
}
