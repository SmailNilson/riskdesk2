export type TzEntry = {
  label: string;
  tz: string;
};

export const TIMEZONES = [
  { label: 'UTC', tz: 'UTC' },
  { label: 'Casablanca', tz: 'Africa/Casablanca' },
  { label: 'Paris', tz: 'Europe/Paris' },
  { label: 'London', tz: 'Europe/London' },
  { label: 'New York', tz: 'America/New_York' },
  { label: 'Chicago', tz: 'America/Chicago' },
  { label: 'Tokyo', tz: 'Asia/Tokyo' },
] as const satisfies readonly TzEntry[];

export const DEFAULT_TIMEZONE = TIMEZONES.find(entry => entry.tz === 'Africa/Casablanca') ?? TIMEZONES[0];

export function findTimezoneByTz(timezone: string) {
  return TIMEZONES.find(entry => entry.tz === timezone) ?? DEFAULT_TIMEZONE;
}
