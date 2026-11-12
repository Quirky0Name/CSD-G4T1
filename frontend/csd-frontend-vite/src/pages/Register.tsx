import { useEffect, useReducer, useRef, useState, type FormEvent, type ReactNode, type Ref } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeftIcon, ArrowRightIcon } from '@heroicons/react/20/solid'
import { DocumentMagnifyingGlassIcon } from '@heroicons/react/24/outline'
import { errorMessage, isConflict } from '../api'
import { useAuth } from '../auth'
import { GeminiNotice, QuestionStep } from '../components/ProfileQuestions'
import SignupIllustration, { type SignupStage } from '../components/SignupIllustration'
import { OTHER } from '../profile/options'
import { toProfile, validateAnswers, type AnswerErrors, type Answers } from '../profile/profileForm'
import { PROFILE_STEPS, isAnswered, stepError, type ProfileStep } from '../profile/questionSteps'
import { MIN_PASSWORD_LENGTH, accountValid, initialRegisterState, registerFlow } from '../profile/registerFlow'

// Sign-up as a short interview: the account, then one optional question per screen, then a review.
// Going back and forth keeps everything (profile/registerFlow.ts); nothing is sent until Create
// account, and that one request makes the account with its profile.

const REVIEW = PROFILE_STEPS.length

const CAPTION: Record<'account' | 'questions' | 'review', string> = {
  account: 'Hear the moment a paper you cite is retracted or corrected.',
  questions: 'A few answers help each report speak to your work.',
  review: 'You can change any answer later in your profile.',
}

const inputClasses =
  'block w-full rounded-md bg-white/5 px-3 py-1.5 text-base text-white outline-1 -outline-offset-1 outline-white/10 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-500 aria-invalid:outline-red-400 sm:text-sm/6'
const labelClasses = 'block text-xs/5 font-medium tracking-wide text-gray-400'
const primaryPill =
  'inline-flex items-center justify-center gap-1.5 rounded-full bg-indigo-500 px-6 py-2.5 text-sm font-semibold text-white shadow-[0_8px_20px_-8px_rgba(99,102,241,0.8)] transition-[background-color,transform] duration-150 hover:bg-indigo-400 active:scale-[0.97] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400 disabled:cursor-wait disabled:opacity-60'
const ghostPill =
  'inline-flex items-center justify-center gap-1.5 rounded-full px-4 py-2.5 text-sm font-semibold text-gray-300 outline-1 -outline-offset-1 outline-white/15 transition-colors duration-150 hover:bg-white/5 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-400 disabled:opacity-50'
const textLink =
  'rounded text-sm font-medium text-gray-400 underline-offset-4 hover:text-white hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400'

export default function Register() {
  const { register } = useAuth()
  const [state, dispatch] = useReducer(registerFlow, undefined, initialRegisterState)
  // which question screen is showing; REVIEW is the last screen, before the account is made
  const [step, setStep] = useState(0)
  const [direction, setDirection] = useState<'forward' | 'back'>('forward')
  const [answerErrors, setAnswerErrors] = useState<AnswerErrors>({})
  const heading = useRef<HTMLHeadingElement>(null)
  const { account, answers, screen, submitting } = state

  const stage: SignupStage = screen === 'account' ? 'account' : step < REVIEW ? PROFILE_STEPS[step] : 'review'

  // focus follows a change of screen, so a keyboard or screen reader user starts at its heading;
  // compared with the last screen shown, not "skip the first run", which StrictMode runs twice
  const shownStage = useRef(stage)
  useEffect(() => {
    if (shownStage.current === stage) return
    shownStage.current = stage
    heading.current?.focus()
  }, [stage])

  const go = (to: number, travel: 'forward' | 'back') => {
    setDirection(travel)
    setStep(to)
  }

  const setAnswers = (next: Answers) => dispatch({ type: 'edit-answers', answers: next })

  const mismatch = account.confirmPassword.length > 0 && account.password !== account.confirmPassword
  const tooShort = account.password.length > 0 && account.password.length < MIN_PASSWORD_LENGTH

  const handleContinue = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (!accountValid(account)) return
    setDirection('forward')
    setStep(0)
    dispatch({ type: 'continue' })
  }

  const handleNext = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (step >= REVIEW) return
    const errors = validateAnswers(answers)
    setAnswerErrors(errors)
    if (stepError(PROFILE_STEPS[step], errors)) return
    go(step + 1, 'forward')
  }

  const handleBack = () => {
    setAnswerErrors({})
    if (step === 0) {
      setDirection('back')
      dispatch({ type: 'back' })
    } else {
      go(step - 1, 'back')
    }
  }

  const handleCreate = async () => {
    if (submitting) return
    const errors = validateAnswers(answers)
    setAnswerErrors(errors)
    const firstWithError = PROFILE_STEPS.findIndex((s) => stepError(s, errors))
    if (firstWithError !== -1) {
      go(firstWithError, 'back')
      return
    }
    dispatch({ type: 'submit' })
    try {
      // success starts the session, and GuestOnly sends the researcher to /
      await register(account.email, account.password, toProfile(answers))
    } catch (err) {
      if (isConflict(err)) {
        setDirection('back')
        dispatch({ type: 'conflict', message: errorMessage(err) })
      } else {
        dispatch({ type: 'failed', message: errorMessage(err) })
      }
    }
  }

  const animation = direction === 'forward' ? 'animate-step-forward' : 'animate-step-back'
  const progress = screen === 'account' ? 0 : step + 1

  return (
    <div className="signup relative grid min-h-svh justify-items-center overflow-hidden bg-gray-950 px-4 py-8 sm:px-6 sm:py-12 md:items-start md:pt-[max(3rem,6vh)]">
      <Backdrop />

      <div className="relative grid w-full max-w-5xl self-start rounded-3xl bg-gray-900 md:min-h-[46rem] shadow-[0_30px_80px_-30px_rgba(0,0,0,0.8)] outline-1 -outline-offset-1 outline-white/10 md:grid-cols-[minmax(0,9fr)_minmax(0,11fr)]">
        <aside className="flex flex-col rounded-t-3xl bg-gray-800/40 px-6 pt-6 pb-6 md:rounded-l-3xl md:rounded-tr-none md:px-10 md:pt-8 md:pb-10">
          <p className="flex items-center gap-2 text-sm font-semibold text-white">
            <DocumentMagnifyingGlassIcon aria-hidden="true" className="size-5 text-indigo-400" />
            Research desk
          </p>
          <div className="mx-auto mt-3 flex w-full max-w-[8rem] flex-col sm:mt-4 sm:max-w-xs md:mt-16 md:max-w-none">
            <SignupIllustration stage={stage} />
          </div>
          {/* on a phone the account screen keeps its form above the fold; its caption returns from sm up */}
          <div className="mx-auto w-full">
            <p
              className={`mx-auto mt-2 max-w-[34ch] text-center text-sm/5 font-medium text-balance text-gray-200 sm:mt-4 md:max-w-[26ch] md:text-base/6 ${
                screen === 'account' ? 'hidden sm:block' : ''
              }`}
            >
              {CAPTION[screen === 'account' ? 'account' : step < REVIEW ? 'questions' : 'review']}
            </p>
          </div>
          <div className="md:mt-auto">
            <ProgressDots current={progress} total={REVIEW + 2} />
          </div>
        </aside>

        <section className="flex min-w-0 flex-col overflow-x-clip px-6 pt-6 pb-8 sm:px-10 md:pt-8 md:pb-10">
          <p className="flex flex-wrap items-center justify-end gap-x-3 gap-y-1 text-sm text-gray-400">
            Already have an account?
            <Link
              to="/signin"
              className="rounded-full px-3.5 py-1 text-sm font-semibold text-gray-200 outline-1 -outline-offset-1 outline-white/20 transition-colors hover:bg-white/5 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-400"
            >
              Sign in
            </Link>
          </p>

          <div key={stage} className={`mt-8 flex flex-1 flex-col md:mt-10 ${animation}`}>
            {screen === 'account' && (
              <form onSubmit={handleContinue} className="flex flex-1 flex-col">
                <Heading headingRef={heading}>Create your account</Heading>
                <p className="mt-2 text-sm/6 text-gray-400">
                  Then five optional questions, about two minutes, so reports fit your work.
                </p>

                <div className="mt-8 space-y-5">
                  <div>
                    <label htmlFor="email" className={labelClasses}>
                      Email address
                    </label>
                    <input
                      id="email"
                      name="email"
                      type="email"
                      required
                      autoComplete="email"
                      value={account.email}
                      onChange={(e) => dispatch({ type: 'edit-account', field: 'email', value: e.target.value })}
                      aria-invalid={state.emailError ? true : undefined}
                      aria-describedby={state.emailError ? 'email-error' : undefined}
                      className={`mt-1.5 ${inputClasses}`}
                    />
                    {state.emailError && (
                      <p id="email-error" role="alert" className="mt-2 text-sm text-red-400">
                        {state.emailError}
                      </p>
                    )}
                  </div>

                  <div>
                    <label htmlFor="password" className={labelClasses}>
                      Password
                    </label>
                    <input
                      id="password"
                      name="password"
                      type="password"
                      required
                      autoComplete="new-password"
                      value={account.password}
                      onChange={(e) => dispatch({ type: 'edit-account', field: 'password', value: e.target.value })}
                      minLength={MIN_PASSWORD_LENGTH}
                      aria-invalid={tooShort}
                      aria-describedby={tooShort ? 'password-error' : undefined}
                      className={`mt-1.5 ${inputClasses}`}
                    />
                    {tooShort && (
                      <p id="password-error" className="mt-2 text-sm text-red-400">
                        Password must be at least {MIN_PASSWORD_LENGTH} characters.
                      </p>
                    )}
                  </div>

                  <div>
                    <label htmlFor="confirm-password" className={labelClasses}>
                      Retype password
                    </label>
                    <input
                      id="confirm-password"
                      name="confirm-password"
                      type="password"
                      required
                      autoComplete="new-password"
                      value={account.confirmPassword}
                      onChange={(e) => dispatch({ type: 'edit-account', field: 'confirmPassword', value: e.target.value })}
                      aria-invalid={mismatch}
                      aria-describedby={mismatch ? 'confirm-password-error' : undefined}
                      className={`mt-1.5 ${inputClasses}`}
                    />
                    {mismatch && (
                      <p id="confirm-password-error" className="mt-2 text-sm text-red-400">
                        Passwords don’t match.
                      </p>
                    )}
                  </div>
                </div>

                <div className="mt-auto pt-10">
                  <button type="submit" className={`w-full sm:w-auto ${primaryPill}`}>
                    Continue
                    <ArrowRightIcon aria-hidden="true" className="size-4" />
                  </button>
                </div>
              </form>
            )}

            {screen === 'questions' && step < REVIEW && (
              <form onSubmit={handleNext} className="flex flex-1 flex-col">
                <Counter current={step + 1} total={REVIEW} />
                <div className="mt-3">
                  <QuestionStep
                    step={PROFILE_STEPS[step]}
                    answers={answers}
                    onChange={setAnswers}
                    errors={answerErrors}
                    headingRef={heading}
                  />
                </div>
                <StepActions onBack={handleBack} notice>
                  {!isAnswered(PROFILE_STEPS[step], answers) && (
                    <button type="button" onClick={() => go(step + 1, 'forward')} className={textLink}>
                      Skip
                    </button>
                  )}
                  <button type="submit" className={primaryPill}>
                    {step === REVIEW - 1 ? 'Review answers' : 'Next'}
                    <ArrowRightIcon aria-hidden="true" className="size-4" />
                  </button>
                </StepActions>
              </form>
            )}

            {screen === 'questions' && step >= REVIEW && (
              <div className="flex flex-1 flex-col">
                <Heading headingRef={heading}>You’re almost in</Heading>
                <p className="mt-2 text-sm/6 text-gray-400">
                  Check your answers, then create your account. Skipped questions stay empty until you fill them in.
                </p>
                <Review answers={answers} onEdit={(i) => go(i, 'back')} />
                {state.error && (
                  <p role="alert" className="mt-6 rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-300 outline-1 -outline-offset-1 outline-red-500/25">
                    {state.error}
                  </p>
                )}
                <StepActions onBack={handleBack} backDisabled={submitting} notice>
                  <button type="button" onClick={handleCreate} disabled={submitting} className={primaryPill}>
                    {submitting ? 'Creating your account…' : 'Create account'}
                  </button>
                </StepActions>
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

function Heading({ children, headingRef }: { children: ReactNode; headingRef: Ref<HTMLHeadingElement> }) {
  return (
    <h2
      ref={headingRef}
      tabIndex={-1}
      className="text-2xl/8 font-semibold tracking-tight text-balance text-white focus:outline-none sm:text-3xl/9"
    >
      {children}
    </h2>
  )
}

// "2 / 5", the number rising in on each step
function Counter({ current, total }: { current: number; total: number }) {
  return (
    <p className="text-3xl/9 font-semibold text-indigo-400 tabular-nums" aria-label={`Question ${current} of ${total}`}>
      <span key={current} className="animate-count-rise inline-block">
        {current}
      </span>
      <span className="text-indigo-300/70"> / {total}</span>
    </p>
  )
}

function ProgressDots({ current, total }: { current: number; total: number }) {
  return (
    <div className="mt-6 flex justify-center gap-1.5" aria-hidden="true">
      {Array.from({ length: total }, (_, i) => (
        <span
          key={i}
          className={`h-1.5 rounded-full transition-[width,background-color] duration-300 ${
            i === current ? 'w-5 bg-indigo-400' : i < current ? 'w-1.5 bg-indigo-400/50' : 'w-1.5 bg-white/15'
          }`}
        />
      ))}
    </div>
  )
}

// the footer pinned to the bottom of the pane: the Gemini notice, then Back and the step's actions
function StepActions({
  onBack,
  backDisabled = false,
  notice = false,
  children,
}: {
  onBack: () => void
  backDisabled?: boolean
  notice?: boolean
  children: ReactNode
}) {
  return (
    <div className="mt-auto pt-10">
      {notice && <GeminiNotice className="mb-6" />}
      <div className="flex items-center justify-between gap-4">
        {/* disabled while the request is out, so its answer always lands on this screen */}
        <button type="button" onClick={onBack} disabled={backDisabled} className={ghostPill}>
          <ArrowLeftIcon aria-hidden="true" className="size-4" />
          Back
        </button>
        <div className="flex items-center gap-5">{children}</div>
      </div>
    </div>
  )
}

const REVIEW_ROWS: { step: ProfileStep; label: string; show: (a: Answers) => string }[] = [
  {
    step: 'occupation',
    label: 'Occupation',
    show: (a) => (a.occupation === OTHER && a.occupationOther.trim() ? a.occupationOther.trim() : a.occupation),
  },
  { step: 'researchFields', label: 'Research fields', show: (a) => a.researchFields.join(', ') },
  { step: 'researchInterests', label: 'Research interests', show: (a) => a.researchInterests.join(', ') },
  { step: 'workTypes', label: 'Type of work', show: (a) => a.workTypes.join(', ') },
  { step: 'responsePreferences', label: 'How reports are written', show: (a) => a.responsePreferences.trim() },
]

function Review({ answers, onEdit }: { answers: Answers; onEdit: (step: number) => void }) {
  return (
    <dl className="mt-6 divide-y divide-white/10 rounded-xl outline-1 -outline-offset-1 outline-white/10">
      {REVIEW_ROWS.map(({ step, label, show }, i) => {
        const value = show(answers)
        return (
          <div key={step} className="flex items-start gap-4 px-4 py-3">
            <div className="min-w-0 flex-1">
              <dt className="text-xs/5 font-medium tracking-wide text-gray-400">{label}</dt>
              <dd className={`mt-0.5 line-clamp-2 text-sm/6 break-words ${value ? 'text-gray-100' : 'text-gray-400 italic'}`}>
                {value || 'Skipped'}
              </dd>
            </div>
            <button type="button" onClick={() => onEdit(i)} className={`mt-2 shrink-0 ${textLink}`}>
              Edit<span className="sr-only"> {label.toLowerCase()}</span>
            </button>
          </div>
        )
      })}
    </dl>
  )
}

// the reference's loose dashed curves, drawn faintly on the app's own ground
function Backdrop() {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 1440 900"
      preserveAspectRatio="xMidYMid slice"
      className="pointer-events-none absolute inset-0 size-full"
    >
      <path d="M-40 180 C 220 60, 380 300, 620 170" stroke="#818cf8" strokeOpacity="0.18" strokeWidth="2" strokeDasharray="6 10" fill="none" />
      <path d="M1480 640 C 1260 760, 1120 520, 900 700" stroke="#818cf8" strokeOpacity="0.18" strokeWidth="2" strokeDasharray="6 10" fill="none" />
      <circle cx="1240" cy="120" r="160" fill="#6366f1" fillOpacity="0.06" />
      <circle cx="160" cy="820" r="200" fill="#6366f1" fillOpacity="0.05" />
    </svg>
  )
}
