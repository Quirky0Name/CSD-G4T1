import type { ChannelOutcome, NotificationPreferences, TestOutcomes, Threshold } from '../api'

// The notification settings' form: one per channel, edited in place and saved on its own, since
// each channel has its own PUT (docs/CONTRACTS.md, "PUT /auth/me/notifications/<channel>").

export type Channel = 'email' | 'telegram'

export type ChannelForm = {
  enabled: boolean
  alert: Threshold
  change: Threshold
  impact: Threshold
  // Telegram only: the chat id as typed; email keeps it empty
  chatId: string
}

export type Forms = Record<Channel, ChannelForm>

// the choices in the order they are shown; null is "never"
export const LEVELS: { value: Threshold; label: string }[] = [
  { value: 'low', label: 'Low' },
  { value: 'medium', label: 'Medium' },
  { value: 'high', label: 'High' },
  { value: null, label: 'Never' },
]

export function fromPreferences(prefs: NotificationPreferences): Forms {
  return {
    email: {
      enabled: prefs.email.enabled,
      alert: prefs.email.min_alert_severity,
      change: prefs.email.min_change_severity,
      impact: prefs.email.min_impact_level,
      chatId: '',
    },
    telegram: {
      enabled: prefs.telegram.enabled,
      alert: prefs.telegram.min_alert_severity,
      change: prefs.telegram.min_change_severity,
      impact: prefs.telegram.min_impact_level,
      chatId: prefs.telegram.chat_id === null ? '' : String(prefs.telegram.chat_id),
    },
  }
}

// The chat id as User Management takes it: a non-zero integer (negative for a group), or null when
// the field is blank. undefined means what was typed isn't one.
export function parseChatId(text: string): number | null | undefined {
  const trimmed = text.trim()
  if (trimmed === '') return null
  if (!/^-?\d+$/.test(trimmed)) return undefined
  const id = Number(trimmed)
  return id !== 0 && Number.isSafeInteger(id) ? id : undefined
}

// the reason the Telegram form can't be saved, or null when it can
export function telegramError(form: ChannelForm): string | null {
  const id = parseChatId(form.chatId)
  if (id === undefined) return 'A chat id is a whole number, such as 123456789 (a group starts with -).'
  if (id === null && form.enabled) return 'Add your chat id to turn Telegram on.'
  return null
}

const thresholds = (form: ChannelForm) => ({
  min_alert_severity: form.alert,
  min_change_severity: form.change,
  min_impact_level: form.impact,
})

export function toEmailBody(form: ChannelForm) {
  return { enabled: form.enabled, ...thresholds(form) }
}

// only for a form telegramError passes
export function toTelegramBody(form: ChannelForm) {
  return { enabled: form.enabled, chat_id: parseChatId(form.chatId) ?? null, ...thresholds(form) }
}

export function isDirty(channel: Channel, form: ChannelForm, saved: ChannelForm): boolean {
  return (
    form.enabled !== saved.enabled ||
    form.alert !== saved.alert ||
    form.change !== saved.change ||
    form.impact !== saved.impact ||
    (channel === 'telegram' && parseChatId(form.chatId) !== parseChatId(saved.chatId))
  )
}

// what a threshold lets through, "at or above" as User Management applies it
export function passes(level: Threshold): string {
  switch (level) {
    case 'low':
      return 'Low, medium and high'
    case 'medium':
      return 'Medium and high'
    case 'high':
      return 'High only'
    default:
      return 'Nothing'
  }
}

const NAMES: Record<Channel, string> = { email: 'Email', telegram: 'Telegram' }

const OUTCOME: Record<ChannelOutcome, string> = {
  sent: 'sent',
  failed: "couldn't be sent",
  disabled: 'is off',
  not_configured: "isn't set up on the server",
}

// the toast after a test: one clause per channel, an error when an enabled channel didn't send
export function testSummary({ channels }: TestOutcomes): { tone: 'success' | 'error' | 'info'; message: string } {
  const order: Channel[] = ['email', 'telegram']
  const message = order.map((c) => `${NAMES[c]} ${OUTCOME[channels[c]]}.`).join(' ')
  const outcomes = order.map((c) => channels[c])
  if (outcomes.some((o) => o === 'failed' || o === 'not_configured')) return { tone: 'error', message }
  if (outcomes.includes('sent')) return { tone: 'success', message: `Test message: ${message}` }
  return { tone: 'info', message: 'Both channels are off, so nothing was sent.' }
}
