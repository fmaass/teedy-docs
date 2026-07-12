export function formatDate(dateStr: string | number, locale?: string): string {
  const value = typeof dateStr === 'string' ? Number(dateStr) || dateStr : dateStr
  return new Date(value).toLocaleDateString(locale, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

// Binary units: formatBytes renders KB/MB/GB against powers of 1024. Any UI that
// lets a user enter a size in these units must convert with the SAME basis, or the
// entered value and the displayed value disagree.
export const BYTES_PER_KB = 1024
export const BYTES_PER_MB = 1024 * 1024
export const BYTES_PER_GB = 1024 * 1024 * 1024

export function formatBytes(bytes: number): string {
  if (bytes < BYTES_PER_KB) return `${bytes} B`
  if (bytes < BYTES_PER_MB) return `${(bytes / BYTES_PER_KB).toFixed(1)} KB`
  if (bytes < BYTES_PER_GB) return `${(bytes / BYTES_PER_MB).toFixed(1)} MB`
  return `${(bytes / BYTES_PER_GB).toFixed(1)} GB`
}

export const formatFileSize = formatBytes
export const formatStorage = formatBytes
