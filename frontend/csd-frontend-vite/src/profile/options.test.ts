import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  GEMINI_NOTICE,
  MAX_INTEREST_LENGTH,
  MAX_INTERESTS,
  MAX_OCCUPATION_OTHER_LENGTH,
  MAX_PREFERENCES_LENGTH,
  OCCUPATIONS,
  PREFERENCES_PLACEHOLDER,
  RESEARCH_FIELDS,
  WORK_TYPES,
} from './options'

// User Management's copy, read only: the two are built from separate Docker contexts, so this test
// is what keeps them the same
const OPTIONS_JSON = fileURLToPath(
  new URL('../../../../users/src/main/resources/profile/options.json', import.meta.url),
)
const server = JSON.parse(readFileSync(OPTIONS_JSON, 'utf8')) as {
  occupations: string[]
  research_fields: string[]
  work_types: string[]
}

describe('the questionnaire lists', () => {
  it("are the same as User Management's", () => {
    expect([...OCCUPATIONS]).toEqual(server.occupations)
    expect([...RESEARCH_FIELDS]).toEqual(server.research_fields)
    expect([...WORK_TYPES]).toEqual(server.work_types)
  })

  it('has the 26 OpenAlex fields', () => {
    expect(RESEARCH_FIELDS).toHaveLength(26)
    expect(RESEARCH_FIELDS).toContain('Economics, Econometrics and Finance')
  })

  it('has the limits User Management applies', () => {
    expect([MAX_INTERESTS, MAX_INTEREST_LENGTH, MAX_OCCUPATION_OTHER_LENGTH, MAX_PREFERENCES_LENGTH]).toEqual([
      5, 60, 60, 500,
    ])
  })

  it('has the exact placeholder and Gemini notice', () => {
    expect(PREFERENCES_PLACEHOLDER).toBe('e.g. Keep it brief and tell me what to do first.')
    expect(GEMINI_NOTICE).toBe(
      'Your answers are sent to Gemini to tailor the explanation and recommendation in your reports.',
    )
  })
})
