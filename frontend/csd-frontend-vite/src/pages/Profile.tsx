import { useEffect, useState, type FormEvent } from 'react'
import { errorMessage, getProfile, saveProfile } from '../api'
import { ActionButton } from '../components/ActionButton'
import ProfileQuestions from '../components/ProfileQuestions'
import { useToast } from '../components/Toasts'
import { emptyAnswers, fromProfile, toProfile, validateAnswers, type AnswerErrors, type Answers } from '../profile/profileForm'

// The researcher's profile: the sign-up questions, to view, edit or clear. An account with no
// profile yet (made before the questionnaire, or that skipped it) sees the empty form, and
// saving it creates the profile. Every answer is optional here too.

type Load =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; answers: Answers; hasProfile: boolean }

function Profile() {
  const showToast = useToast()
  const [load, setLoad] = useState<Load>({ status: 'loading' })
  const [errors, setErrors] = useState<AnswerErrors>({})
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  // bumped by Try again, which reruns the load
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    getProfile()
      .then((profile) => {
        if (!active) return
        setLoad(
          profile
            ? { status: 'ready', answers: fromProfile(profile), hasProfile: true }
            : { status: 'ready', answers: emptyAnswers(), hasProfile: false },
        )
      })
      .catch((err: unknown) => {
        if (active) setLoad({ status: 'error', message: errorMessage(err) })
      })
    return () => {
      active = false
    }
  }, [attempt])

  const tryAgain = () => {
    setLoad({ status: 'loading' })
    setAttempt((n) => n + 1)
  }

  const edit = (answers: Answers) =>
    setLoad((current) => (current.status === 'ready' ? { ...current, answers } : current))

  const handleSave = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (load.status !== 'ready' || saving) return
    const found = validateAnswers(load.answers)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    setSaveError(null)
    setSaving(true)
    try {
      // the reply is the profile as stored (trimmed, blanks cleared), so the form shows exactly that
      const saved = await saveProfile(toProfile(load.answers))
      setLoad({ status: 'ready', answers: fromProfile(saved), hasProfile: true })
      showToast({ tone: 'success', message: 'Your profile is saved.' })
    } catch (err) {
      // the edits stay, so the researcher can fix what the server refused
      setSaveError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <main className="px-4 py-10 sm:px-6 lg:px-8">
      <div className="mb-6 max-w-2xl">
        <h1 className="text-2xl/7 font-bold text-white sm:text-3xl sm:tracking-tight">Your profile</h1>
        <p className="mt-2 text-sm/6 text-gray-400">
          Your answers shape how your reports are written. Every question is optional, and changes apply to reports
          assessed from now on.
        </p>
      </div>

      {load.status === 'loading' && <p className="text-sm text-gray-400">Loading your profile…</p>}

      {load.status === 'error' && (
        <div className="flex max-w-2xl flex-wrap items-center gap-3 rounded-md bg-red-500/10 px-3 py-2 text-sm text-red-400 outline outline-red-500/20">
          <p role="alert" className="min-w-0 flex-1">
            Couldn't load your profile: {load.message}
          </p>
          <ActionButton onClick={tryAgain}>Try again</ActionButton>
        </div>
      )}

      {load.status === 'ready' && (
        <form onSubmit={handleSave} className="max-w-2xl space-y-8">
          {!load.hasProfile && (
            <p className="rounded-md bg-indigo-500/10 px-3 py-2 text-sm text-indigo-300 outline outline-indigo-500/20">
              You haven't filled in your profile yet.
            </p>
          )}

          <ProfileQuestions answers={load.answers} onChange={edit} errors={errors} />

          {saveError && (
            <p role="alert" className="text-sm text-red-400">
              {saveError}
            </p>
          )}

          <ActionButton type="submit" tone="primary" disabled={saving}>
            {saving ? 'Saving…' : 'Save'}
          </ActionButton>
        </form>
      )}
    </main>
  )
}

export default Profile
