// The questionnaire's form state, used by sign-up and the profile page: what an empty form is, the
// limits checked before sending (User Management checks them again, with the rest of its rules),
// and turning the form into the profile body and back.
import type { Profile } from '../api'
import {
  MAX_INTEREST_LENGTH,
  MAX_INTERESTS,
  MAX_OCCUPATION_OTHER_LENGTH,
  MAX_PREFERENCES_LENGTH,
  OTHER,
} from './options'

// text inputs hold '' for no answer, as a controlled input needs
export type Answers = {
  occupation: string
  occupationOther: string
  researchFields: string[]
  researchInterests: string[]
  workTypes: string[]
  responsePreferences: string
}

export type AnswerErrors = Partial<Record<'occupationOther' | 'researchInterests' | 'responsePreferences', string>>

export function emptyAnswers(): Answers {
  return {
    occupation: '',
    occupationOther: '',
    researchFields: [],
    researchInterests: [],
    workTypes: [],
    responsePreferences: '',
  }
}

/** Only the limits: every answer is optional. Lengths count after trimming, as the server does. */
export function validateAnswers(answers: Answers): AnswerErrors {
  const errors: AnswerErrors = {}
  if (answers.occupation === OTHER && answers.occupationOther.trim().length > MAX_OCCUPATION_OTHER_LENGTH) {
    errors.occupationOther = `Please use at most ${MAX_OCCUPATION_OTHER_LENGTH} characters.`
  }
  if (answers.researchInterests.length > MAX_INTERESTS) {
    errors.researchInterests = `You can add up to ${MAX_INTERESTS} interests.`
  } else if (answers.researchInterests.some((interest) => interest.trim().length > MAX_INTEREST_LENGTH)) {
    errors.researchInterests = `Each interest can be at most ${MAX_INTEREST_LENGTH} characters.`
  }
  if (answers.responsePreferences.trim().length > MAX_PREFERENCES_LENGTH) {
    errors.responsePreferences = `Please use at most ${MAX_PREFERENCES_LENGTH} characters.`
  }
  return errors
}

const textOrNull = (text: string) => text.trim() || null

/** The profile body: text trimmed, blank text null, and the other occupation only with Other. */
export function toProfile(answers: Answers): Profile {
  return {
    occupation: answers.occupation || null,
    occupation_other: answers.occupation === OTHER ? textOrNull(answers.occupationOther) : null,
    research_fields: [...answers.researchFields],
    research_interests: answers.researchInterests.map((interest) => interest.trim()),
    work_types: [...answers.workTypes],
    response_preferences: textOrNull(answers.responsePreferences),
  }
}

/** A saved profile as the form shows it, for the profile page. */
export function fromProfile(profile: Profile): Answers {
  return {
    occupation: profile.occupation ?? '',
    occupationOther: profile.occupation_other ?? '',
    researchFields: [...profile.research_fields],
    researchInterests: [...profile.research_interests],
    workTypes: [...profile.work_types],
    responsePreferences: profile.response_preferences ?? '',
  }
}
