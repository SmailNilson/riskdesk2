import { TickMarkType, Time } from 'lightweight-charts';

type DateInput = string | number | Date;

function toDate(value: DateInput): Date | null {
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value;
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function chartTimeToDate(value: Time): Date | null {
  if (typeof value === 'number') {
    return toDate(value * 1000);
  }
  if (typeof value === 'string') {
    return toDate(value);
  }
  return toDate(Date.UTC(value.year, value.month - 1, value.day));
}

export function formatInTimezone(
  value: DateInput,
  timezone: string,
  options: Intl.DateTimeFormatOptions,
  locale?: string,
) {
  const date = toDate(value);
  if (!date) {
    return '—';
  }
  return new Intl.DateTimeFormat(locale, {
    ...options,
    timeZone: timezone,
  }).format(date);
}

export function formatDateTime(value: DateInput, timezone: string, locale?: string) {
  return formatInTimezone(value, timezone, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }, locale);
}

export function formatTime(value: DateInput, timezone: string, locale?: string) {
  return formatInTimezone(value, timezone, {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }, locale);
}

export function makeChartTimeFormatter(timezone: string, locale = 'en-GB') {
  return (timestamp: number) => formatInTimezone(timestamp * 1000, timezone, {
    day: '2-digit',
    month: 'short',
    year: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }, locale);
}

export function makeChartTickFormatter(timezone: string, locale = 'en-GB') {
  return (time: Time, tickMarkType: TickMarkType) => {
    const date = chartTimeToDate(time);
    if (!date) {
      return null;
    }

    const options: Intl.DateTimeFormatOptions =
      tickMarkType === TickMarkType.Year
        ? { year: 'numeric' }
        : tickMarkType === TickMarkType.Month
          ? { month: 'short' }
          : tickMarkType === TickMarkType.DayOfMonth
            ? { day: '2-digit' }
            : tickMarkType === TickMarkType.TimeWithSeconds
              ? { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }
              : { hour: '2-digit', minute: '2-digit', hour12: false };

    return formatInTimezone(date, timezone, options, locale);
  };
}
