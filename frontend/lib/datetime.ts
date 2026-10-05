/**
 * 将后端返回的 ISO 时间（如 2026-10-03T07:51:16.495986Z）
 * 统一格式化为 yyyy-MM-dd HH:mm:ss（浏览器本地时区）。
 * 空值返回 '-'，无法解析时原样返回字符串。
 */
export function formatDateTime(value?: string | number | Date | null): string {
  if (value === null || value === undefined || value === '') return '-';
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime())) {
    return typeof value === 'string' ? value : '-';
  }
  const p = (n: number) => String(n).padStart(2, '0');
  return (
    `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ` +
    `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  );
}
