import { useEffect, useReducer, useRef, useState, type FormEvent, type ReactNode, type Ref } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeftIcon, ArrowRightIcon } from '@heroicons/react/20/solid'
import { errorMessage, isConflict } from '../api'
import { useAuth } from '../auth'
import { GeminiNotice, QuestionStep } from '../components/ProfileQuestions'
import { AuthShell } from '../components/AuthShell'
import { SIGNUP_POSE, SignupBadge, type SignupStage } from '../components/SignupIllustration'
import { ghostPill, inputClasses, labelClasses, primaryPill, smallPill, textLink } from '../ui'
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
    <AuthShell
      pose={SIGNUP_POSE[stage]}
      poseKey={stage}
      badge={<SignupBadge stage={stage} />}
      caption={CAPTION[screen === 'account' ? 'account' : step < REVIEW ? 'questions' : 'review']}
      // on a phone the account screen keeps its form above the fold; its caption returns from sm up
      hideCaptionOnPhone={screen === 'account'}
      footer={<ProgressDots current={progress} total={REVIEW + 2} />}
      topRight={
        <>
          <span className="hidden min-[380px]:inline">Already have an account?</span>
          <Link to="/signin" className={smallPill}>
            Sign in
          </Link>
        </>
      }
    >
          <div key={stage} className={`mt-6 flex flex-1 flex-col md:mt-10 short:md:mt-4 ${animation}`}>
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

                <div className="mt-auto pt-10 short:pt-6">
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
    </AuthShell>
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
    <div className="flex gap-1.5 sm:mt-6 sm:justify-center short:md:mt-3" aria-hidden="true">
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
    <div className="mt-auto pt-10 short:pt-6">
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
