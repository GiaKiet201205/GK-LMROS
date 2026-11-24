export const PORTAL = import.meta.env.VITE_PORTAL || 'dispatcher'
export const IS_DRIVER = PORTAL === 'driver'
export const IS_CUSTOMER = PORTAL === 'customer'
export const AUTH_BASE = IS_CUSTOMER ? '/api/customer/auth' : '/api/auth'
