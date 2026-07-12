export function number(value: number | undefined) {
  return new Intl.NumberFormat().format(value ?? 0);
}

export function ms(value: number | undefined) {
  return `${(value ?? 0).toFixed(2)} ms`;
}

export function dateTime(value: string | undefined | null) {
  if (!value) return '-';
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}

export function statusTone(status: number) {
  if (status >= 500) return 'bad';
  if (status === 429 || status === 401 || status === 403) return 'warn';
  if (status >= 400) return 'bad';
  return 'good';
}
