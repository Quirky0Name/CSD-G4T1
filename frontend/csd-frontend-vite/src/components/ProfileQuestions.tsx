import TagInput from './TagInput'
import { INTEREST_SUGGESTIONS } from '../profile/interestSuggestions'
import {
  GEMINI_NOTICE,
  MAX_INTEREST_LENGTH,
  MAX_INTERESTS,
  MAX_OCCUPATION_OTHER_LENGTH,
  MAX_PREFERENCES_LENGTH,
  OCCUPATIONS,
  OTHER,
  PREFERENCES_PLACEHOLDER,
  RESEARCH_FIELDS,
  WORK_TYPES,
} from '../profile/options'
import type { AnswerErrors, Answers } from '../profile/profileForm'

// The questionnaire, the same on sign-up and the profile page (docs/CONTRACTS.md, the profile
// body). Every answer is optional. It only edits the answers it's given; the page sends them.

type ProfileQuestionsProps = {
  answers: Answers
  onChange: (answers: Answers) => void
  errors?: AnswerErrors
}

const labelClasses = 'block text-sm/6 font-medium text-gray-900 dark:text-gray-100'
const errorClasses = 'mt-2 text-sm text-red-600 dark:text-red-400'
const fieldClasses =
  'block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-600 sm:text-sm/6 dark:bg-white/5 dark:text-white dark:outline-white/10 dark:placeholder:text-gray-500 dark:focus:outline-indigo-500'

export default function ProfileQuestions({ answers, onChange, errors = {} }: ProfileQuestionsProps) {
  const set = (change: Partial<Answers>) => onChange({ ...answers, ...change })

  const toggleWorkType = (workType: string, checked: boolean) =>
    set({
      // kept in the list's order, whatever order they were ticked in
      workTypes: WORK_TYPES.filter((type) => (type === workType ? checked : answers.workTypes.includes(type))),
    })

  return (
    <div className="space-y-6">
      <p className="rounded-md bg-gray-50 px-3 py-2 text-sm text-gray-700 dark:bg-white/5 dark:text-gray-300">
        {GEMINI_NOTICE}
      </p>

      <div>
        <label htmlFor="occupation" className={labelClasses}>
          What is your occupation?
        </label>
        <div className="mt-2">
          <select
            id="occupation"
            value={answers.occupation}
            // anything but Other hides the box and clears it, so no stale text is kept
            onChange={(e) =>
              set({
                occupation: e.target.value,
                occupationOther: e.target.value === OTHER ? answers.occupationOther : '',
              })
            }
            className={fieldClasses}
          >
            <option value="">No answer</option>
            {OCCUPATIONS.map((occupation) => (
              <option key={occupation} value={occupation}>
                {occupation}
              </option>
            ))}
          </select>
        </div>
        {answers.occupation === OTHER && (
          <div className="mt-3">
            <label htmlFor="occupation-other" className={labelClasses}>
              Please specify your occupation
            </label>
            <div className="mt-2">
              <input
                id="occupation-other"
                type="text"
                maxLength={MAX_OCCUPATION_OTHER_LENGTH}
                value={answers.occupationOther}
                onChange={(e) => set({ occupationOther: e.target.value })}
                aria-invalid={errors.occupationOther ? true : undefined}
                aria-describedby={errors.occupationOther ? 'occupation-other-error' : undefined}
                className={fieldClasses}
              />
            </div>
            {errors.occupationOther && (
              <p id="occupation-other-error" className={errorClasses}>
                {errors.occupationOther}
              </p>
            )}
          </div>
        )}
      </div>

      <TagInput
        id="research-fields"
        label="What are your research fields?"
        value={answers.researchFields}
        onChange={(researchFields) => set({ researchFields })}
        options={RESEARCH_FIELDS}
        allowCustom={false}
      />

      <TagInput
        id="research-interests"
        label="What are your research interests?"
        value={answers.researchInterests}
        onChange={(researchInterests) => set({ researchInterests })}
        options={INTEREST_SUGGESTIONS}
        allowCustom
        maxTags={MAX_INTERESTS}
        maxTagLength={MAX_INTEREST_LENGTH}
        error={errors.researchInterests}
      />

      <fieldset>
        <legend className={labelClasses}>What type of work do you do?</legend>
        <div className="mt-2 grid gap-2 sm:grid-cols-2">
          {WORK_TYPES.map((workType) => (
            <label key={workType} className="flex items-start gap-2 text-sm text-gray-900 dark:text-gray-100">
              <input
                type="checkbox"
                checked={answers.workTypes.includes(workType)}
                onChange={(e) => toggleWorkType(workType, e.target.checked)}
                className="mt-0.5 size-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
              />
              {workType}
            </label>
          ))}
        </div>
      </fieldset>

      <div>
        <label htmlFor="response-preferences" className={labelClasses}>
          How would you like your reports written?
        </label>
        <div className="mt-2">
          <textarea
            id="response-preferences"
            rows={3}
            maxLength={MAX_PREFERENCES_LENGTH}
            placeholder={PREFERENCES_PLACEHOLDER}
            value={answers.responsePreferences}
            onChange={(e) => set({ responsePreferences: e.target.value })}
            aria-invalid={errors.responsePreferences ? true : undefined}
            aria-describedby={
              errors.responsePreferences ? 'response-preferences-count response-preferences-error' : 'response-preferences-count'
            }
            className={fieldClasses}
          />
        </div>
        <p id="response-preferences-count" className="mt-1 text-right text-xs text-gray-500 dark:text-gray-400">
          {answers.responsePreferences.length}/{MAX_PREFERENCES_LENGTH}
        </p>
        {errors.responsePreferences && (
          <p id="response-preferences-error" className={errorClasses}>
            {errors.responsePreferences}
          </p>
        )}
      </div>
    </div>
  )
}
