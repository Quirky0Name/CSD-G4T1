// Sign-up's two screens as one state, so going between them loses nothing: the account (email and
// password, checked locally before Continue) and the questionnaire. One register request is sent
// from the questions screen; a taken email (409) goes back to the account screen with the answers
// kept. Nothing is saved to localStorage, so a reload starts over.
import { emptyAnswers, type Answers } from './profileForm'

// matches User Management's register validation (docs/CONTRACTS.md)
export const MIN_PASSWORD_LENGTH = 8

export type Account = { email: string; password: string; confirmPassword: string }

export type RegisterState = {
  screen: 'account' | 'questions'
  account: Account
  answers: Answers
  // the server's message for a taken email, shown on the email field until the email changes
  emailError: string | null
  // any other failure, shown on the questions screen
  error: string | null
  submitting: boolean
}

export type RegisterAction =
  | { type: 'edit-account'; field: keyof Account; value: string }
  | { type: 'edit-answers'; answers: Answers }
  | { type: 'continue' }
  | { type: 'back' }
  | { type: 'submit' }
  | { type: 'conflict'; message: string }
  | { type: 'failed'; message: string }

export function initialRegisterState(): RegisterState {
  return {
    screen: 'account',
    account: { email: '', password: '', confirmPassword: '' },
    answers: emptyAnswers(),
    emailError: null,
    error: null,
    submitting: false,
  }
}

/** The local checks before Continue; the browser checks the email's format on its own. */
export function accountValid(account: Account): boolean {
  return (
    account.email.trim() !== '' &&
    account.password.length >= MIN_PASSWORD_LENGTH &&
    account.password === account.confirmPassword
  )
}

export function registerFlow(state: RegisterState, action: RegisterAction): RegisterState {
  switch (action.type) {
    case 'edit-account':
      return {
        ...state,
        account: { ...state.account, [action.field]: action.value },
        emailError: action.field === 'email' ? null : state.emailError,
      }
    case 'edit-answers':
      return { ...state, answers: action.answers }
    case 'continue':
      return accountValid(state.account) ? { ...state, screen: 'questions', emailError: null, error: null } : state
    case 'back':
      return { ...state, screen: 'account', error: null }
    case 'submit':
      return state.screen === 'questions' && !state.submitting ? { ...state, submitting: true, error: null } : state
    case 'conflict':
      return { ...state, screen: 'account', emailError: action.message, submitting: false }
    case 'failed':
      return { ...state, error: action.message, submitting: false }
  }
}
