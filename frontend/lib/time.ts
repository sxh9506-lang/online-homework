function getZonedParts(date: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    year: Number(values.year),
    month: Number(values.month),
    day: Number(values.day),
    hour: Number(values.hour),
    minute: Number(values.minute),
  };
}

export function formatInTimeZone(instant?: string | null, timeZone = "UTC") {
  if (!instant) return "未设置截止时间";
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return "时间无效";
  try {
    return new Intl.DateTimeFormat("zh-CN", {
      timeZone,
      year: "numeric",
      month: "long",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZoneName: "short",
    }).format(date);
  } catch {
    return new Intl.DateTimeFormat("zh-CN", {
      year: "numeric",
      month: "long",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    }).format(date);
  }
}

/** Convert a datetime-local value interpreted in the supplied IANA zone to UTC. */
export function localDateTimeToUtc(value: string, timeZone: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(value);
  if (!match) throw new Error("请填写有效的截止日期和时间。");
  const [, y, m, d, h, min] = match;
  const target = {
    year: Number(y),
    month: Number(m),
    day: Number(d),
    hour: Number(h),
    minute: Number(min),
  };
  const targetAsUtc = Date.UTC(target.year, target.month - 1, target.day, target.hour, target.minute);
  let candidate = targetAsUtc;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const actual = getZonedParts(new Date(candidate), timeZone);
    const actualAsUtc = Date.UTC(actual.year, actual.month - 1, actual.day, actual.hour, actual.minute);
    const delta = targetAsUtc - actualAsUtc;
    if (delta === 0) break;
    candidate += delta;
  }
  const resolved = getZonedParts(new Date(candidate), timeZone);
  if (
    resolved.year !== target.year || resolved.month !== target.month ||
    resolved.day !== target.day || resolved.hour !== target.hour ||
    resolved.minute !== target.minute
  ) {
    throw new Error("该班级时区在此时刻存在夏令时切换，请选择另一个时间。");
  }
  return new Date(candidate).toISOString();
}

export function isPast(instant?: string | null) {
  return Boolean(instant && new Date(instant).getTime() < Date.now());
}

export function utcToLocalDateTime(instant: string, timeZone: string): string {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return "";
  const parts = getZonedParts(date, timeZone);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}T${pad(parts.hour)}:${pad(parts.minute)}`;
}
