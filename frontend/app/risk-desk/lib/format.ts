// Locale-pinned formatters so SSR (host OS locale) matches CSR (browser locale).
const L = 'en-US';

export const fmt = {
  px: (v: number, d = 2) => v.toLocaleString(L, { minimumFractionDigits: d, maximumFractionDigits: d }),
  pxFx: (v: number) => v.toFixed(4),
  signed: (v: number, d = 2) =>
    (v >= 0 ? '+' : '') + v.toLocaleString(L, { minimumFractionDigits: d, maximumFractionDigits: d }),
  pct: (v: number, d = 1) => (v >= 0 ? '+' : '') + (v * 100).toFixed(d) + '%',
  money: (v: number) =>
    (v >= 0 ? '+$' : '−$') + Math.abs(v).toLocaleString(L, { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
  moneyAbs: (v: number) =>
    '$' + Math.abs(v).toLocaleString(L, { minimumFractionDigits: 0, maximumFractionDigits: 0 }),
};
