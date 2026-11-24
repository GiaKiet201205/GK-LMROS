import test from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_PREFERENCES, normalizePreferences, resolveTheme } from '../src/portals/dispatcher/preferences/preferences.js'

test('invalid or missing cached values cannot apply unknown themes or warehouses', () => {
  assert.deepEqual(normalizePreferences(null), DEFAULT_PREFERENCES)
  assert.deepEqual(normalizePreferences({ theme: 'garbage', density: 'tiny', notificationToasts: 'false', defaultWarehouse: -1 }), DEFAULT_PREFERENCES)
  assert.deepEqual(normalizePreferences({ theme: 'dark', density: 'compact', notificationToasts: false, defaultWarehouse: 2 }), { theme: 'dark', density: 'compact', notificationToasts: false, defaultWarehouse: 2 })
})

test('system appearance follows the device while explicit selections stay fixed', () => {
  assert.equal(resolveTheme('system', true), 'dark')
  assert.equal(resolveTheme('system', false), 'light')
  assert.equal(resolveTheme('light', true), 'light')
  assert.equal(resolveTheme('dark', false), 'dark')
})
