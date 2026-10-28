import {describe, expect, it} from 'vitest'
import {isDecimal} from './[=feature].decimal'

describe('decimal wire values', () => {
  it('checks precision and scale without converting the value to a number', () => {
    expect(isDecimal('9007199254740993.01', 20, 2)).toBe(true)
    expect(isDecimal('9007199254740993.001', 20, 2)).toBe(false)
    expect(isDecimal('1.2E+3', 6, 2)).toBe(true)
    expect(isDecimal('1E-7', 8, 7)).toBe(true)
    expect(isDecimal('1E-7', 8, 2)).toBe(false)
    expect(isDecimal('1E+100000000', 20, 2)).toBe(false)
    expect(isDecimal('invalid', 20, 2)).toBe(false)
  })
})
