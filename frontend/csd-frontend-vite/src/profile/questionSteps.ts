// Sign-up asks the questionnaire one question per screen. The order, whether a question has an
// answer (to offer Skip or Next), and which limit error belongs to which screen.
import type { AnswerErrors, Answers } from './profileForm'

export const PROFILE_STEPS = [
  'occupation',
  'researchFields',
  'researchInterests',
  'workTypes',
  'responsePreferences',
] as const

export type ProfileStep = (typeof PROFILE_STEPS)[number]

export function isAnswered(step: ProfileStep, answers: Answers): boolean {
  switch (step) {
    case 'occupation':
      return answers.occupation !== ''
    case 'responsePreferences':
      return answers.responsePreferences.trim() !== ''
    default:
      return answers[step].length > 0
  }
}

// the limit errors validateAnswers can give, by the screen that shows them
const ERROR_FOR_STEP: Partial<Record<ProfileStep, keyof AnswerErrors>> = {
  occupation: 'occupationOther',
  researchInterests: 'researchInterests',
  responsePreferences: 'responsePreferences',
}

export function stepError(step: ProfileStep, errors: AnswerErrors): string | undefined {
  const key = ERROR_FOR_STEP[step]
  return key ? errors[key] : undefined
}
