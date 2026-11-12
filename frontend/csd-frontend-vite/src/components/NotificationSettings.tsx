import { useEffect, useId, useRef, useState, type ComponentType, type FormEvent, type ReactNode, type SVGProps } from 'react'
import { Switch } from '@headlessui/react'
import { BellAlertIcon, ChatBubbleLeftRightIcon, EnvelopeIcon, PaperAirplaneIcon } from '@heroicons/react/24/outline'
import { errorMessage, getNotifications, saveEmailNotifications, saveTelegramNotifications, sendTestNotification, type Threshold } from '../api'
import { ActionButton } from './ActionButton'
import { Loading, Section } from './Page'
import { useToast } from './Toasts'
import { errorPanel, inputClasses } from '../ui'
import {
  LEVELS,
  fromPreferences,
  isDirty,
  passes,
  telegramError,
  testSummary,
  toEmailBody,
  toTelegramBody,
  type Channel,
  type ChannelForm,
  type Forms,
} from '../profile/notificationForm'

// Outside notifications on the profile: email and Telegram, each switched on and off, with its own
// minimum levels for alerts and for reports, and saved on its own (one PUT per channel). A test
// message checks whatever is saved as on. The bot token is the server's; the researcher types only
// their chat id (docs/CONTRACTS.md, "GET /auth/me/notifications").

type Load =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  // saved is what the server holds; forms are the edits in place
  | { status: 'ready'; forms: Forms; saved: Forms; address: string }

type PerChannel<T> = Record<Channel, T>

export default function NotificationSettings({ index }: { index: number }) {
  const showToast = useToast()
  const [load, setLoad] = useState<Load>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [saving, setSaving] = useState<PerChannel<boolean>>({ email: false, telegram: false })
  const [errors, setErrors] = useState<PerChannel<string | null>>({ email: null, telegram: null })
  const [testing, setTesting] = useState(false)

  useEffect(() => {
    let active = true
    getNotifications()
      .then((prefs) => {
        if (!active) return
        const forms = fromPreferences(prefs)
        setLoad({ status: 'ready', forms, saved: forms, address: prefs.email.address })
      })
      .catch((err: unknown) => {
        if (active) setLoad({ status: 'error', message: errorMessage(err) })
      })
    return () => {
      active = false
    }
  }, [attempt])

  const edit = (channel: Channel, form: ChannelForm) => {
    setLoad((current) => (current.status === 'ready' ? { ...current, forms: { ...current.forms, [channel]: form } } : current))
    setErrors((current) => ({ ...current, [channel]: null }))
  }

  const reset = (channel: Channel) => {
    if (load.status === 'ready') edit(channel, load.saved[channel])
  }

  const save = async (channel: Channel) => {
    if (load.status !== 'ready' || saving[channel]) return
    const form = load.forms[channel]
    if (channel === 'telegram') {
      const problem = telegramError(form)
      if (problem) {
        setErrors((current) => ({ ...current, telegram: problem }))
        return
      }
    }
    setSaving((current) => ({ ...current, [channel]: true }))
    try {
      const prefs = channel === 'email' ? await saveEmailNotifications(toEmailBody(form)) : await saveTelegramNotifications(toTelegramBody(form))
      const stored = fromPreferences(prefs)
      // the reply holds both channels; only this one's edits are replaced, the other's stay
      setLoad((current) =>
        current.status === 'ready'
          ? { ...current, saved: stored, forms: { ...current.forms, [channel]: stored[channel] }, address: prefs.email.address }
          : current,
      )
      showToast({ tone: 'success', message: channel === 'email' ? 'Email settings saved.' : 'Telegram settings saved.' })
    } catch (err) {
      setErrors((current) => ({ ...current, [channel]: errorMessage(err) }))
    } finally {
      setSaving((current) => ({ ...current, [channel]: false }))
    }
  }

  const test = async () => {
    if (testing) return
    setTesting(true)
    try {
      showToast(testSummary(await sendTestNotification()))
    } catch (err) {
      showToast({ tone: 'error', message: errorMessage(err) })
    } finally {
      setTesting(false)
    }
  }

  return (
    <Section
      id="notifications"
      title="Notifications"
      icon={BellAlertIcon}
      index={index}
      action={
        load.status === 'ready' && (
          <ActionButton onClick={test} disabled={testing}>
            <PaperAirplaneIcon aria-hidden="true" className="size-4" />
            {testing ? 'Sending…' : 'Send a test'}
          </ActionButton>
        )
      }
    >
      <p className="max-w-[65ch] text-sm/6 text-gray-400">
        Hear about a tracked paper by email or Telegram, even with no tab open: when it gets new alerts, and when its report is
        ready. Each channel keeps its own levels. A test goes to every channel saved as on.
      </p>

      {load.status === 'loading' && (
        <div className="mt-6">
          <Loading label="Loading your notification settings…" />
        </div>
      )}

      {load.status === 'error' && (
        <div className={`mt-6 flex flex-wrap items-center gap-3 ${errorPanel}`}>
          <p role="alert" className="min-w-0 flex-1">
            Couldn't load your notification settings: {load.message}
          </p>
          <ActionButton
            onClick={() => {
              setLoad({ status: 'loading' })
              setAttempt((n) => n + 1)
            }}
          >
            Try again
          </ActionButton>
        </div>
      )}

      {load.status === 'ready' && (
        <div className="mt-6 grid gap-4 sm:gap-5 lg:grid-cols-2">
          <ChannelPanel
            channel="email"
            name="Email"
            icon={EnvelopeIcon}
            destination={`To ${load.address}`}
            form={load.forms.email}
            dirty={isDirty('email', load.forms.email, load.saved.email)}
            saving={saving.email}
            error={errors.email}
            onChange={(form) => edit('email', form)}
            onReset={() => reset('email')}
            onSave={() => save('email')}
          />
          <ChannelPanel
            channel="telegram"
            name="Telegram"
            icon={ChatBubbleLeftRightIcon}
            destination={load.saved.telegram.chatId ? `To chat ${load.saved.telegram.chatId}` : 'No chat id yet'}
            form={load.forms.telegram}
            dirty={isDirty('telegram', load.forms.telegram, load.saved.telegram)}
            saving={saving.telegram}
            error={errors.telegram}
            onChange={(form) => edit('telegram', form)}
            onReset={() => reset('telegram')}
            onSave={() => save('telegram')}
          />
        </div>
      )}
    </Section>
  )
}

function ChannelPanel({
  channel,
  name,
  icon: Icon,
  destination,
  form,
  dirty,
  saving,
  error,
  onChange,
  onReset,
  onSave,
}: {
  channel: Channel
  name: string
  icon: ComponentType<SVGProps<SVGSVGElement>>
  destination: string
  form: ChannelForm
  dirty: boolean
  saving: boolean
  error: string | null
  onChange: (form: ChannelForm) => void
  onReset: () => void
  onSave: () => void
}) {
  const chatIdField = useId()
  const errorId = useId()
  const telegram = channel === 'telegram'
  // the chat id field owns the error when it is about the chat id, so the outline and the message meet
  const chatIdInvalid = telegram && error !== null && /chat.?id/i.test(error)
  const chatIdInput = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (chatIdInvalid) chatIdInput.current?.focus()
  }, [chatIdInvalid, error])

  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    onSave()
  }

  return (
    <form
      onSubmit={submit}
      aria-label={`${name} notifications`}
      noValidate
      className="flex min-w-0 flex-col rounded-2xl bg-white/[0.03] p-4 outline-1 -outline-offset-1 outline-white/5 sm:p-5"
    >
      <div className="flex items-center gap-3">
        <span
          aria-hidden="true"
          className={`grid size-10 shrink-0 place-items-center rounded-full transition-colors ${form.enabled ? 'bg-indigo-500/15 text-indigo-300' : 'bg-white/5 text-gray-400'}`}
        >
          <Icon className="size-5" />
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="text-base/6 font-semibold text-white">{name}</h3>
          <p className="truncate text-sm/5 text-gray-400" title={destination}>
            {destination}
          </p>
        </div>
        <span aria-hidden="true" className={`text-sm font-medium ${form.enabled ? 'text-indigo-300' : 'text-gray-500'}`}>
          {form.enabled ? 'On' : 'Off'}
        </span>
        <Switch
          checked={form.enabled}
          onChange={(enabled) => onChange({ ...form, enabled })}
          aria-label={`Send ${name} notifications`}
          className="group relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full bg-white/10 p-0.5 outline-1 -outline-offset-1 outline-white/15 transition-colors duration-200 data-checked:bg-indigo-500 data-checked:outline-indigo-400 data-focus:outline-2 data-focus:outline-offset-2 data-focus:outline-indigo-400"
        >
          <span className="size-5 rounded-full bg-white shadow-sm transition-transform duration-200 ease-out group-data-checked:translate-x-5" />
        </Switch>
      </div>

      {telegram && (
        <div className="mt-5">
          <label htmlFor={chatIdField} className="block text-sm/6 font-medium text-gray-200">
            Chat id
          </label>
          <input
            ref={chatIdInput}
            id={chatIdField}
            inputMode="numeric"
            autoComplete="off"
            spellCheck={false}
            placeholder="123456789"
            value={form.chatId}
            onChange={(e) => onChange({ ...form, chatId: e.target.value })}
            aria-invalid={chatIdInvalid || undefined}
            aria-describedby={chatIdInvalid ? errorId : `${chatIdField}-help`}
            className={`mt-1.5 tabular-nums ${inputClasses}`}
          />
          {chatIdInvalid && (
            <p id={errorId} role="alert" className="mt-1.5 text-sm/6 text-red-400">
              {error}
            </p>
          )}
          <ol id={`${chatIdField}-help`} className="mt-3 space-y-1 text-sm/6 text-gray-400">
            <Step n={1}>In Telegram, open the project's bot and press Start, so it can message you.</Step>
            <Step n={2}>
              Message <span className="font-medium text-gray-200">@userinfobot</span>; it replies with your id.
            </Step>
            <Step n={3}>Paste that number here. A group's id starts with a minus sign.</Step>
          </ol>
        </div>
      )}

      <div className="mt-6 space-y-5 border-t border-white/5 pt-5">
        <LevelPicker
          name={`${channel}-alert`}
          label="Alerts"
          hint="A retraction, correction or delisting on a tracked paper"
          value={form.alert}
          onChange={(alert) => onChange({ ...form, alert })}
        />
        <div>
          <p className="text-sm/6 font-medium text-gray-200">Reports</p>
          <p className="text-sm/6 text-gray-400">A report is sent when either of these reaches its level.</p>
          <div className="mt-3 space-y-4">
            <LevelPicker
              name={`${channel}-change`}
              label="Change severity"
              value={form.change}
              onChange={(change) => onChange({ ...form, change })}
              nested
            />
            <LevelPicker
              name={`${channel}-impact`}
              label="Impact on your draft"
              value={form.impact}
              onChange={(impact) => onChange({ ...form, impact })}
              nested
            />
          </div>
        </div>
      </div>

      {error && !chatIdInvalid && (
        <p role="alert" className={`mt-5 ${errorPanel}`}>
          {error}
        </p>
      )}

      <div className="mt-auto flex flex-wrap items-center justify-end gap-2 pt-6">
        {dirty && !saving && (
          <span className="mr-auto text-sm text-gray-400" aria-live="polite">
            Unsaved changes
          </span>
        )}
        {dirty && (
          <ActionButton tone="quiet" onClick={onReset} disabled={saving}>
            Undo
          </ActionButton>
        )}
        <ActionButton type="submit" tone="primary" disabled={!dirty || saving}>
          {saving ? 'Saving…' : `Save ${telegram ? 'Telegram' : 'email'}`}
        </ActionButton>
      </div>
    </form>
  )
}

function Step({ n, children }: { n: number; children: ReactNode }) {
  return (
    <li className="flex gap-2.5">
      <span
        aria-hidden="true"
        className="mt-0.5 grid size-5 shrink-0 place-items-center rounded-full bg-white/5 text-xs font-semibold text-gray-300 tabular-nums"
      >
        {n}
      </span>
      <span className="min-w-0">{children}</span>
    </li>
  )
}

// A minimum level as a pill track: Low, Medium, High or Never, with what it lets through beside the
// label. Real radios under the pills, so arrow keys and screen readers work as on any radio group.
function LevelPicker({
  name,
  label,
  hint,
  value,
  onChange,
  nested = false,
}: {
  name: string
  label: string
  hint?: string
  value: Threshold
  onChange: (value: Threshold) => void
  nested?: boolean
}) {
  const labelId = useId()
  const hintId = useId()
  return (
    <div role="radiogroup" aria-labelledby={labelId} aria-describedby={hint ? hintId : undefined} className="min-w-0">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <span id={labelId} className={`text-sm/6 ${nested ? 'text-gray-300' : 'font-medium text-gray-200'}`}>
          {label}
        </span>
        <span className="text-sm/6 text-gray-400">{value === null ? 'Never sent' : passes(value)}</span>
      </div>
      {hint && (
        <p id={hintId} className="text-sm/6 text-gray-400">
          {hint}
        </p>
      )}
      <div className="mt-2 grid grid-cols-4 gap-1 rounded-full bg-white/5 p-1 outline-1 -outline-offset-1 outline-white/10">
        {LEVELS.map((level) => (
          <label
            key={level.label}
            className="relative cursor-pointer rounded-full px-1 py-1.5 text-center text-sm/5 font-semibold text-gray-400 transition-[background-color,color] duration-150 select-none hover:text-white has-checked:bg-indigo-500 has-checked:text-white has-checked:shadow-[0_8px_20px_-8px_rgba(99,102,241,0.8)] has-focus-visible:outline-2 has-focus-visible:outline-offset-1 has-focus-visible:outline-indigo-400"
          >
            <input
              type="radio"
              name={name}
              checked={value === level.value}
              onChange={() => onChange(level.value)}
              className="sr-only"
            />
            {level.label}
          </label>
        ))}
      </div>
    </div>
  )
}
