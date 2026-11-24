export const DEFAULT_PREFERENCES = { theme: 'system', density: 'comfortable', notificationToasts: true, defaultWarehouse: null }
export function normalizePreferences(value) {
  return {
    theme: ['light', 'dark', 'system'].includes(value?.theme) ? value.theme : 'system',
    density: ['comfortable', 'compact'].includes(value?.density) ? value.density : 'comfortable',
    notificationToasts: typeof value?.notificationToasts === 'boolean' ? value.notificationToasts : true,
    defaultWarehouse: Number.isInteger(value?.defaultWarehouse) && value.defaultWarehouse > 0 ? value.defaultWarehouse : null,
  }
}
export const resolveTheme = (theme, systemDark) => theme === 'system' ? systemDark ? 'dark' : 'light' : theme
