function stripTrailingSlash(value: string | undefined): string {
  return value ? value.replace(/\/$/, '') : '';
}

export const API_BASE = stripTrailingSlash(process.env.NEXT_PUBLIC_API_URL);
export const WS_BASE = stripTrailingSlash(process.env.NEXT_PUBLIC_WS_URL);
