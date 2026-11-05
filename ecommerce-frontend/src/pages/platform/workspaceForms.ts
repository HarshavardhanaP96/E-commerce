const UNITS = ["B", "KB", "MB", "GB", "TB"];

export const formatBytes = (n: number) => {
  let value = n;
  let unit = 0;
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${Number(value.toFixed(1))} ${UNITS[unit]}`;
};

export const usagePercent = (used: number, limit: number) =>
  limit <= 0 ? 0 : Math.min(100, Math.round((used / limit) * 100));
