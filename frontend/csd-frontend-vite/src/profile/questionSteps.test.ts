import { describe, expect, it } from 'vitest'
import { emptyAnswers } from './profileForm'
import { PROFILE_STEPS, isAnswered, stepError } from './questionSteps'

describe('PROFILE_STEPS', () => {
  it('asks the five questions in the questionnaire order', () => {
    expect(PROFILE_STEPS).toEqual([
      'occupation',
      'researchFields',
      'researchInterests',
      'workTypes',
      'responsePreferences',
    ])
  })
})

describe('isAnswered', () => {
  it('is false for every step of an empty questionnaire', () => {
    for (const step of PROFILE_STEPS) expect(isAnswered(step, emptyAnswers())).toBe(false)
  })

  it('reads each step from its own answer', () => {
    const answers = {
      ...emptyAnswers(),
      occupation: 'Clinician',
      researchFields: ['Medicine'],
      researchInterests: ['vaccine safety'],
      workTypes: ['Clinical'],
      responsePreferences: 'Brief.',
    }
    for (const step of PROFILE_STEPS) expect(isAnswered(step, answers)).toBe(true)
  })

  it('treats blank preferences as unanswered', () => {
    expect(isAnswered('responsePreferences', { ...emptyAnswers(), responsePreferences: '  \n ' })).toBe(false)
  })
})

describe('stepError', () => {
  const errors = {
    occupationOther: 'Please use at most 60 characters.',
    researchInterests: 'You can add up to 5 interests.',
    responsePreferences: 'Please use at most 500 characters.',
  }

  it("gives each step only its own field's error", () => {
    expect(stepError('occupation', errors)).toBe('Please use at most 60 characters.')
    expect(stepError('researchInterests', errors)).toBe('You can add up to 5 interests.')
    expect(stepError('responsePreferences', errors)).toBe('Please use at most 500 characters.')
    expect(stepError('researchFields', errors)).toBeUndefined()
    expect(stepError('workTypes', errors)).toBeUndefined()
  })

  it('is undefined with no errors', () => {
    expect(stepError('occupation', {})).toBeUndefined()
  })
})
