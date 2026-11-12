import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRightIcon } from '@heroicons/react/20/solid'
import { errorMessage } from '../api'
import { useAuth } from '../auth'
import { AuthShell } from '../components/AuthShell'
import { errorPanel, inputClasses, labelClasses, primaryPill, smallPill } from '../ui'

// Sign-in on the sign-up card: the penguin, turned 3/4, points across at the form, and turns to its !
// when the details are refused.

export default function Signin() {
  const { login } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    try {
      await login(email, password)
    } catch (err) {
      setError(errorMessage(err))
      setSubmitting(false)
    }
  }

  return (
    <AuthShell
      pose={error ? 'alert' : 'point'}
      caption="Welcome back. Your papers have been watched while you were away."
      hideCaptionOnPhone
      topRight={
        <>
          <span className="hidden min-[380px]:inline">New here?</span>
          <Link to="/register" className={smallPill}>
            Create account
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="animate-step-forward mt-6 flex flex-1 flex-col md:mt-10 short:md:mt-4">
        <h1 className="text-2xl/8 font-semibold tracking-tight text-balance text-white sm:text-3xl/9">Sign in to your desk</h1>
        <p className="mt-2 text-sm/6 text-gray-400">See which of the papers you cite have changed since you last looked.</p>

        <div className="mt-8 space-y-5 short:mt-5 short:space-y-3">
          <div>
            <label htmlFor="email" className={labelClasses}>
              Email address
            </label>
            <input
              id="email"
              name="email"
              type="text"
              inputMode="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              aria-invalid={error ? true : undefined}
              className={`mt-1.5 ${inputClasses}`}
            />
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
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-invalid={error ? true : undefined}
              className={`mt-1.5 ${inputClasses}`}
            />
          </div>

          {error && (
            <p role="alert" className={`animate-reveal ${errorPanel}`}>
              {error}
            </p>
          )}
        </div>

        <div className="mt-auto pt-10 short:pt-6">
          <button type="submit" disabled={submitting} className={`w-full sm:w-auto ${primaryPill}`}>
            {submitting ? 'Signing in…' : 'Sign in'}
            {!submitting && <ArrowRightIcon aria-hidden="true" className="size-4" />}
          </button>
        </div>
      </form>
    </AuthShell>
  )
}
