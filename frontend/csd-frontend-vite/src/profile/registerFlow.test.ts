import { describe, expect, it } from 'vitest'
import { emptyAnswers } from './profileForm'
import { accountValid, initialRegisterState, registerFlow, type RegisterAction, type RegisterState } from './registerFlow'

const ANSWERS = {
  ...emptyAnswers(),
  occupation: 'Other',
  occupationOther: 'Librarian',
  researchFields: ['Medicine'],
  researchInterests: ['vaccine safety'],
  responsePreferences: 'Brief, please.',
}

function run(...actions: RegisterAction[]): RegisterState {
  return actions.reduce(registerFlow, initialRegisterState())
}

const filledAccount: RegisterAction[] = [
  { type: 'edit-account', field: 'email', value: 'a@b.c' },
  { type: 'edit-account', field: 'password', value: 'correct horse' },
  { type: 'edit-account', field: 'confirmPassword', value: 'correct horse' },
]

describe('registerFlow', () => {
  it('starts on the account screen with nothing filled in', () => {
    const state = initialRegisterState()
    expect(state.screen).toBe('account')
    expect(state.answers).toEqual(emptyAnswers())
    expect(state.account).toEqual({ email: '', password: '', confirmPassword: '' })
  })

  it('keeps the account and every answer through next, back and next', () => {
    const state = run(
      ...filledAccount,
      { type: 'continue' },
      { type: 'edit-answers', answers: ANSWERS },
      { type: 'back' },
      { type: 'edit-account', field: 'email', value: 'new@b.c' },
      { type: 'continue' },
    )

    expect(state.screen).toBe('questions')
    expect(state.answers).toEqual(ANSWERS)
    expect(state.account).toEqual({ email: 'new@b.c', password: 'correct horse', confirmPassword: 'correct horse' })
  })

  it.each([
    ['no email', { email: '' }],
    ['a short password', { password: 'seven77', confirmPassword: 'seven77' }],
    ['passwords that differ', { confirmPassword: 'correct horsE' }],
  ])('never reaches the questions with %s', (_, change) => {
    let state = run(...filledAccount)
    for (const [field, value] of Object.entries(change)) {
      state = registerFlow(state, { type: 'edit-account', field: field as 'email', value })
    }

    expect(registerFlow(state, { type: 'continue' }).screen).toBe('account')
  })

  it('a taken email goes back to the account screen with the message on the email, every answer kept', () => {
    const state = run(
      ...filledAccount,
      { type: 'continue' },
      { type: 'edit-answers', answers: ANSWERS },
      { type: 'submit' },
      { type: 'conflict', message: 'An account with this email already exists' },
    )

    expect(state.screen).toBe('account')
    expect(state.emailError).toBe('An account with this email already exists')
    expect(state.answers).toEqual(ANSWERS)
    expect(state.submitting).toBe(false)
    const edited = registerFlow(state, { type: 'edit-account', field: 'email', value: 'x@b.c' })
    expect(edited.emailError).toBeNull()
    const again = registerFlow(state, { type: 'continue' })
    expect(again.screen).toBe('questions')
    expect(again.emailError).toBeNull()
    expect(again.answers).toEqual(ANSWERS)
  })

  it('any other failure stays on the questions with the message', () => {
    const state = run(...filledAccount, { type: 'continue' }, { type: 'submit' }, { type: 'failed', message: 'Network Error' })

    expect(state.screen).toBe('questions')
    expect(state.error).toBe('Network Error')
    expect(state.submitting).toBe(false)
  })

  it('submitting clears the last error and can only happen on the questions screen', () => {
    const failed = run(...filledAccount, { type: 'continue' }, { type: 'submit' }, { type: 'failed', message: 'x' })
    const retry = registerFlow(failed, { type: 'submit' })
    expect(retry.error).toBeNull()
    expect(retry.submitting).toBe(true)

    expect(run(...filledAccount, { type: 'submit' }).submitting).toBe(false)
  })
})

describe('accountValid', () => {
  it('needs an email, a password of at least 8 characters, and the same password twice', () => {
    expect(accountValid({ email: 'a@b.c', password: '12345678', confirmPassword: '12345678' })).toBe(true)
    expect(accountValid({ email: 'a@b.c', password: '1234567', confirmPassword: '1234567' })).toBe(false)
    expect(accountValid({ email: '  ', password: '12345678', confirmPassword: '12345678' })).toBe(false)
    expect(accountValid({ email: 'a@b.c', password: '12345678', confirmPassword: '12345679' })).toBe(false)
  })
})
