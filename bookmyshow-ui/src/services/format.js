export const dateTime = (value) => value ? new Date(value).toLocaleString() : 'Not available';
export const money = (amount, currency) => new Intl.NumberFormat(undefined, {
  style: 'currency', currency,
}).format(amount);
export const validId = (value) => /^[1-9]\d*$/.test(String(value)) && Number.isSafeInteger(Number(value));
export const terminalBooking = (status) => ['CONFIRMED', 'CANCELLED'].includes(status);
