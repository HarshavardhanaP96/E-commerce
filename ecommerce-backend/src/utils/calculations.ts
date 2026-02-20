/**
 * Calculate percentage change between two values
 * @param thisMonth - Current period value
 * @param lastMonth - Previous period value
 * @returns Percentage as a number
 */
export const calculatePercentage = (thisMonth: number, lastMonth: number) => {
  if (lastMonth === 0) return thisMonth * 100;
  const percentage = (thisMonth / lastMonth) * 100;
  return Number(percentage.toFixed(0));
};
