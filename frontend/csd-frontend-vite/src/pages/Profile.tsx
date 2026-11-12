import { useEffect, useState, type FormEvent } from 'react'
import { errorMessage, getProfile, saveProfile } from '../api'
import { ActionButton } from '../components/ActionButton'
import NotificationSettings from '../components/NotificationSettings'
import ProfileQuestions from '../components/ProfileQuestions'
import { useToast } from '../components/Toasts'
import { Loading, Page, PageHero, Section } from '../components/Page'
import { PencilSquareIcon, UserIcon } from '@heroicons/react/24/outline'
import { useAuth } from '../auth'
import { OTHER } from '../profile/options'
import { errorPanel, primaryPill, signalLink } from '../ui'
import { emptyAnswers, fromProfile, toProfile, validateAnswers, type AnswerErrors, type Answers } from '../profile/profileForm'

// The researcher's profile: the sign-up questions, to view, edit or clear. An account with no
// profile yet (made before the questionnaire, or that skipped it) sees the empty form, and
// saving it creates the profile. Every answer is optional here too.

type Load =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  // saved is what the server holds, shown in the summary cards; answers is the form, edited in place
  | { status: 'ready'; answers: Answers; saved: Answers; hasProfile: boolean }

function Profile() {
  const { session } = useAuth()
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
            ? { status: 'ready', answers: fromProfile(profile), saved: fromProfile(profile), hasProfile: true }
            : { status: 'ready', answers: emptyAnswers(), saved: emptyAnswers(), hasProfile: false },
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
      setLoad({ status: 'ready', answers: fromProfile(saved), saved: fromProfile(saved), hasProfile: true })
      showToast({ tone: 'success', message: 'Your profile is saved.' })
    } catch (err) {
      // the edits stay, so the researcher can fix what the server refused
      setSaveError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const saved = load.status === 'ready' ? load.saved : null
  const occupation = saved ? (saved.occupation === OTHER ? saved.occupationOther.trim() || OTHER : saved.occupation) : ''
  const joined = session?.user.created_at && !Number.isNaN(Date.parse(session.user.created_at)) ? formatDate(session.user.created_at) : null

  return (
    <Page
      hero={
        <PageHero
          tag="Researcher"
          initial={(session?.user.email?.[0] ?? 'U').toUpperCase()}
          title="Your profile"
          description="Your answers shape how your reports are written. Every question is optional, and changes apply to reports assessed from now on."
          facts={[
            { label: 'Email', value: session?.user.email ?? 'Not available' },
            { label: 'Occupation', value: occupation || 'Not set' },
          ]}
          factsEnd={joined && <span>Member since {joined}</span>}
        />
      }
    >
      {load.status === 'loading' && <Loading label="Loading your profile…" />}

      {load.status === 'error' && (
        <div className={`flex flex-wrap items-center gap-3 ${errorPanel}`}>
          <p role="alert" className="min-w-0 flex-1">
            Couldn't load your profile: {load.message}
          </p>
          <ActionButton onClick={tryAgain}>Try again</ActionButton>
        </div>
      )}

      {load.status === 'ready' && (
        <div className="space-y-5 sm:space-y-6">
          <div className="grid items-stretch gap-5 sm:gap-6 md:grid-cols-[minmax(0,1fr)_17rem]">
            <Section id="about-work" title="About your work" icon={UserIcon} index={1}>
              <dl className="space-y-2.5 text-sm/6">
                <Line label="Research fields" value={load.saved.researchFields.join(', ')} />
                <Line label="Research interests" value={load.saved.researchInterests.join(', ')} />
                <Line label="Type of work" value={load.saved.workTypes.join(', ')} />
              </dl>
            </Section>

            {/* the narrow card: how reports are written, or a pointer to the question when nothing is set */}
            <Section id="report-voice" title="Report voice" index={2} className="flex flex-col">
              {load.saved.responsePreferences.trim() ? (
                <p className="line-clamp-6 text-sm/6 text-gray-300">{load.saved.responsePreferences.trim()}</p>
              ) : (
                <p className="text-sm/6 text-gray-400">
                  No preference yet, so reports use their default voice.{' '}
                  <a href="#edit-answers" className={signalLink}>
                    Set one below
                  </a>
                </p>
              )}
            </Section>
          </div>

          <NotificationSettings index={3} />

          <Section id="edit-answers" title="Edit your answers" icon={PencilSquareIcon} index={4}>
            <form onSubmit={handleSave} className="max-w-3xl space-y-8">
              <ProfileQuestions answers={load.answers} onChange={edit} errors={errors} />

              {saveError && (
                <p role="alert" className={errorPanel}>
                  {saveError}
                </p>
              )}

              <div className="border-t border-white/10 pt-6">
                <button type="submit" disabled={saving} className={`w-full sm:w-auto ${primaryPill}`}>
                  {saving ? 'Saving…' : 'Save profile'}
                </button>
              </div>
            </form>
          </Section>
        </div>
      )}
    </Page>
  )
}

// "Label: value", the reference's About lines; an empty answer says so rather than leaving a gap
function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-wrap gap-x-1.5">
      <dt className="font-semibold text-gray-200">{label}:</dt>
      <dd className={value ? 'text-gray-400' : 'text-gray-500 italic'}>{value || 'Not answered'}</dd>
    </div>
  )
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' })
}

export default Profile
