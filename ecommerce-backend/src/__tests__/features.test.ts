import { calculatePercentage } from '../utils/calculations.js';

describe('calculatePercentage', () => {
  it('should return 100 times this month if last month is 0', () => {
    expect(calculatePercentage(10, 0)).toBe(1000);
  });

  it('should return correct percentage increase', () => {
    // 50 vs 40 -> (50/40) * 100 = 125
    expect(calculatePercentage(50, 40)).toBe(125);
  });

  it('should return correct percentage decrease', () => {
    // 20 vs 40 -> (20/40) * 100 = 50
    expect(calculatePercentage(20, 40)).toBe(50);
  });
});
