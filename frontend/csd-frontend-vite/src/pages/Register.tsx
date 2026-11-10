import { useEffect, useReducer, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage, isConflict } from '../api'
import { useAuth } from '../auth'
import ProfileQuestions from '../components/ProfileQuestions'
import { toProfile, validateAnswers, type AnswerErrors } from '../profile/profileForm'
import { MIN_PASSWORD_LENGTH, initialRegisterState, registerFlow } from '../profile/registerFlow'

// Sign-up in two screens: the account, then the questionnaire. Going between them keeps
// everything (profile/registerFlow.ts); one request makes the account with its profile.

const inputClasses =
  'block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-600 sm:text-sm/6 dark:bg-white/5 dark:text-white dark:outline-white/10 dark:placeholder:text-gray-500 dark:focus:outline-indigo-500'
const labelClasses = 'block text-sm/6 font-medium text-gray-900 dark:text-gray-100'
const primaryButton =
  'flex w-full justify-center rounded-md bg-indigo-600 px-3 py-1.5 text-sm/6 font-semibold text-white shadow-xs hover:bg-indigo-500 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 disabled:opacity-60 dark:bg-indigo-500 dark:shadow-none dark:hover:bg-indigo-400 dark:focus-visible:outline-indigo-500'
const secondaryButton =
  'flex w-full justify-center rounded-md bg-white px-3 py-1.5 text-sm/6 font-semibold text-gray-900 shadow-xs ring-1 ring-gray-300 ring-inset hover:bg-gray-50 disabled:opacity-60 dark:bg-white/10 dark:text-white dark:ring-white/10 dark:hover:bg-white/20'

export default function Register() {
  const { register } = useAuth()
  const [state, dispatch] = useReducer(registerFlow, undefined, initialRegisterState)
  const [answerErrors, setAnswerErrors] = useState<AnswerErrors>({})
  const heading = useRef<HTMLHeadingElement>(null)
  const shownScreen = useRef(state.screen)
  const { account, answers, screen, submitting } = state

  // focus follows a change of screen, so a keyboard or screen reader user starts at its heading;
  // compared with the last screen shown, not "skip the first run", which StrictMode runs twice
  useEffect(() => {
    if (shownScreen.current === screen) return
    shownScreen.current = screen
    heading.current?.focus()
  }, [screen])

  const mismatch = account.confirmPassword.length > 0 && account.password !== account.confirmPassword
  const tooShort = account.password.length > 0 && account.password.length < MIN_PASSWORD_LENGTH

  const handleContinue = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    dispatch({ type: 'continue' })
  }

  const handleCreate = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (submitting) return
    const errors = validateAnswers(answers)
    setAnswerErrors(errors)
    if (Object.keys(errors).length > 0) return
    dispatch({ type: 'submit' })
    try {
      // success starts the session, and GuestOnly sends the researcher to /
      await register(account.email, account.password, toProfile(answers))
    } catch (err) {
      dispatch(isConflict(err) ? { type: 'conflict', message: errorMessage(err) } : { type: 'failed', message: errorMessage(err) })
    }
  }

  return (
    <div className="flex min-h-full flex-col justify-center px-6 py-12 lg:px-8">
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <p className="text-center text-sm text-gray-500 dark:text-gray-400">
          Step {screen === 'account' ? 1 : 2} of 2
        </p>
        <h2
          ref={heading}
          tabIndex={-1}
          className="mt-1 text-center text-2xl/9 font-bold tracking-tight text-gray-900 focus:outline-none dark:text-white"
        >
          {screen === 'account' ? 'Create your account' : 'Tell us about your research'}
        </h2>
        {screen === 'questions' && (
          <p className="mt-2 text-center text-sm text-gray-600 dark:text-gray-400">
            Every question is optional, and you can change your answers later in your profile.
          </p>
        )}
      </div>

      <div className="mt-10 sm:mx-auto sm:w-full sm:max-w-md">
        {screen === 'account' ? (
          <form onSubmit={handleContinue} className="space-y-6">
            <div>
              <label htmlFor="email" className={labelClasses}>
                Email address
              </label>
              <div className="mt-2">
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
                  className={inputClasses}
                />
              </div>
              {state.emailError && (
                <p id="email-error" role="alert" className="mt-2 text-sm text-red-600 dark:text-red-400">
                  {state.emailError}
                </p>
              )}
            </div>

            <div>
              <label htmlFor="password" className={labelClasses}>
                Password
              </label>
              <div className="mt-2">
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
                  className={inputClasses}
                />
              </div>
              {tooShort && (
                <p id="password-error" className="mt-2 text-sm text-red-600 dark:text-red-400">
                  Password must be at least {MIN_PASSWORD_LENGTH} characters.
                </p>
              )}
            </div>

            <div>
              <label htmlFor="confirm-password" className={labelClasses}>
                Retype password
              </label>
              <div className="mt-2">
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
                  className={inputClasses}
                />
              </div>
              {mismatch && (
                <p id="confirm-password-error" className="mt-2 text-sm text-red-600 dark:text-red-400">
                  Passwords don’t match.
                </p>
              )}
            </div>

            <button type="submit" className={primaryButton}>
              Continue
            </button>
          </form>
        ) : (
          <form onSubmit={handleCreate} className="space-y-8">
            <ProfileQuestions
              answers={answers}
              onChange={(next) => dispatch({ type: 'edit-answers', answers: next })}
              errors={answerErrors}
            />

            {state.error && (
              <p role="alert" className="text-sm text-red-600 dark:text-red-400">
                {state.error}
              </p>
            )}

            <div className="flex flex-col-reverse gap-3 sm:flex-row">
              {/* disabled while the request is out, so its answer always lands on this screen */}
              <button
                type="button"
                disabled={submitting}
                onClick={() => dispatch({ type: 'back' })}
                className={secondaryButton}
              >
                Back
              </button>
              <button type="submit" disabled={submitting} className={primaryButton}>
                Create account
              </button>
            </div>
          </form>
        )}

        <p className="mt-10 text-center text-sm/6 text-gray-500 dark:text-gray-400">
          Already a member?{' '}
          <Link
            to="/signin"
            className="font-semibold text-indigo-600 hover:text-indigo-500 dark:text-indigo-400 dark:hover:text-indigo-300"
          >
            Sign in
          </Link>
        </p>
      </div>
    </div>
  )
}
