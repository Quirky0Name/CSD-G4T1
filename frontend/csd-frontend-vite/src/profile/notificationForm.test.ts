import { describe, expect, it } from 'vitest'
import type { NotificationPreferences } from '../api'
import { fromPreferences, isDirty, parseChatId, passes, telegramError, testSummary, toEmailBody, toTelegramBody } from './notificationForm'

const defaults: NotificationPreferences = {
  email: { enabled: false, address: 'a@b.c', min_alert_severity: 'high', min_change_severity: 'medium', min_impact_level: 'medium' },
  telegram: { enabled: false, chat_id: null, min_alert_severity: 'high', min_change_severity: 'medium', min_impact_level: 'medium' },
}

describe('fromPreferences', () => {
  it('reads each channel, a missing chat id as blank', () => {
    expect(fromPreferences(defaults)).toEqual({
      email: { enabled: false, alert: 'high', change: 'medium', impact: 'medium', chatId: '' },
      telegram: { enabled: false, alert: 'high', change: 'medium', impact: 'medium', chatId: '' },
    })
  })

  it('shows a stored chat id as typed, negative included', () => {
    const forms = fromPreferences({ ...defaults, telegram: { ...defaults.telegram, enabled: true, chat_id: -100123 } })
    expect(forms.telegram.chatId).toBe('-100123')
  })
})

describe('parseChatId', () => {
  it('takes a blank field as no chat id', () => {
    expect(parseChatId('  ')).toBeNull()
  })

  it('takes a positive or negative whole number', () => {
    expect(parseChatId(' 123456789 ')).toBe(123456789)
    expect(parseChatId('-1001234567890')).toBe(-1001234567890)
  })

  it('refuses zero, a fraction, text and a number too big to keep exactly', () => {
    for (const text of ['0', '-0', '1.5', '12a', '@me', '99999999999999999']) {
      expect(parseChatId(text)).toBeUndefined()
    }
  })
})

describe('telegramError', () => {
  const form = fromPreferences(defaults).telegram

  it('allows Telegram off with no chat id', () => {
    expect(telegramError(form)).toBeNull()
  })

  it('asks for a chat id before Telegram can be turned on', () => {
    expect(telegramError({ ...form, enabled: true })).toBe('Add your chat id to turn Telegram on.')
  })

  it('names a chat id that is not a whole number, on or off', () => {
    expect(telegramError({ ...form, chatId: 'abc' })).toMatch(/whole number/)
  })
})

describe('bodies', () => {
  it('sends a never threshold as null', () => {
    const form = { ...fromPreferences(defaults).email, enabled: true, alert: null }
    expect(toEmailBody(form)).toEqual({ enabled: true, min_alert_severity: null, min_change_severity: 'medium', min_impact_level: 'medium' })
  })

  it('sends the chat id as a number, and a blank one as null', () => {
    const form = { ...fromPreferences(defaults).telegram, enabled: true, chatId: '42' }
    expect(toTelegramBody(form)).toMatchObject({ enabled: true, chat_id: 42 })
    expect(toTelegramBody({ ...form, enabled: false, chatId: '' })).toMatchObject({ chat_id: null })
  })
})

describe('isDirty', () => {
  const saved = fromPreferences(defaults)

  it('is clean for the saved form', () => {
    expect(isDirty('email', saved.email, saved.email)).toBe(false)
  })

  it('notices a changed threshold or switch', () => {
    expect(isDirty('email', { ...saved.email, impact: 'low' }, saved.email)).toBe(true)
    expect(isDirty('telegram', { ...saved.telegram, enabled: true }, saved.telegram)).toBe(true)
  })

  it('compares chat ids as numbers, so spaces alone change nothing', () => {
    const stored = { ...saved.telegram, chatId: '42' }
    expect(isDirty('telegram', { ...stored, chatId: ' 42 ' }, stored)).toBe(false)
    expect(isDirty('telegram', { ...stored, chatId: '43' }, stored)).toBe(true)
  })
})

describe('passes', () => {
  it('reads a threshold as at or above', () => {
    expect(passes('low')).toBe('Low, medium and high')
    expect(passes('medium')).toBe('Medium and high')
    expect(passes('high')).toBe('High only')
    expect(passes(null)).toBe('Nothing')
  })
})

describe('testSummary', () => {
  it('confirms a test that reached every enabled channel', () => {
    expect(testSummary({ channels: { email: 'sent', telegram: 'disabled' } })).toEqual({
      tone: 'success',
      message: 'Test message: Email sent. Telegram is off.',
    })
  })

  it('reports an enabled channel that did not send as an error', () => {
    expect(testSummary({ channels: { email: 'sent', telegram: 'failed' } })).toEqual({
      tone: 'error',
      message: "Email sent. Telegram couldn't be sent.",
    })
    expect(testSummary({ channels: { email: 'not_configured', telegram: 'disabled' } }).tone).toBe('error')
  })

  it('says nothing was sent when both channels are off', () => {
    expect(testSummary({ channels: { email: 'disabled', telegram: 'disabled' } })).toEqual({
      tone: 'info',
      message: 'Both channels are off, so nothing was sent.',
    })
  })
})
