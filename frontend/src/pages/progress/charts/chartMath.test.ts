import { describe, expect, it } from 'vitest'
import {
  activityLevel,
  linePaths,
  percent,
  segmentLayout,
  shares,
} from './chartMath'

describe('chart maths', () => {
  it('bins activity into four levels of the busiest day', () => {
    expect(activityLevel(0, 20)).toBe(0)
    expect(activityLevel(1, 20)).toBe(1)
    expect(activityLevel(5, 20)).toBe(1)
    expect(activityLevel(6, 20)).toBe(2)
    expect(activityLevel(15, 20)).toBe(3)
    expect(activityLevel(20, 20)).toBe(4)
    expect(activityLevel(3, 0)).toBe(0)
  })

  it('breaks a line where a week has no value', () => {
    const x = (i: number) => i * 10
    const y = (v: number) => 100 - v * 100
    expect(linePaths([0.5, 0.75, undefined, 1, 0.5], x, y)).toEqual([
      'M0,50 L10,25',
      'M30,0 L40,50',
    ])
    // A lone point draws no path; the chart gives it a dot
    expect(linePaths([undefined, 0.5, undefined], x, y)).toEqual([])
  })

  it('lays out segments with a surface gap between visible blocks', () => {
    expect(shares([1, 3, 0, 0])).toEqual([0.25, 0.75, 0, 0])
    expect(shares([0, 0])).toEqual([0, 0])
    const layout = segmentLayout([0.25, 0.75, 0, 0], 202)
    expect(layout[0]).toEqual({ left: 0, width: 50 })
    expect(layout[1]).toEqual({ left: 52, width: 150 })
    expect(layout[2]!.width).toBe(0)
    expect(percent(0.875)).toBe('88%')
  })
})
