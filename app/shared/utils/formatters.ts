/**
 * Format a date string as relative time (e.g., "2m ago", "3h ago", "5d ago")
 * @param dateString - ISO date string or null/undefined
 * @returns Formatted relative time string or empty string if invalid
 */
export const formatRelativeTime = (dateString: string | null | undefined): string => {
  if (!dateString) return ""
  const date = new Date(dateString)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMs / 3600000)
  const diffDays = Math.floor(diffMs / 86400000)

  if (diffMins < 1) return "now"
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  if (diffDays < 7) return `${diffDays}d ago`
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" }).format(date)
}
