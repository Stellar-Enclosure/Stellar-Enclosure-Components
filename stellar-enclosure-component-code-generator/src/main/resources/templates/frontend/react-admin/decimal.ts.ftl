/** Validate decimal strings without converting them to an imprecise JavaScript number. */
export const isDecimal = (value: unknown, precision?: number, scale = 0): value is string => {
  if (typeof value !== 'string') return false
  const match = /^-?(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(value)
  if (!match) return false
  const exponent = Number(match[3] ?? '0')
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 10_000) return false
  if (precision === undefined) return true
  if (!Number.isSafeInteger(precision) || !Number.isSafeInteger(scale) || precision < 1 || scale < 0) return false
  const fraction = match[2] ?? ''
  const shift = exponent - fraction.length + scale
  if (shift < 0) return false
  const significant = (match[1] + fraction).replace(/^0+/, '')
  return significant.length === 0 || significant.length + shift <= precision
}
