import { type Ref } from 'react'
import { CheckIcon, InformationCircleIcon } from '@heroicons/react/20/solid'
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
import type { ProfileStep } from '../profile/questionSteps'

// The questionnaire (docs/CONTRACTS.md, the profile body), with the exact wording the spec fixes.
// The profile page shows every question at once (ProfileQuestions); sign-up asks one per screen
// (QuestionStep). Every answer is optional. These only edit the answers they're given.

const QUESTION: Record<ProfileStep, string> = {
  occupation: 'What is your occupation?',
  researchFields: 'What are your research fields?',
  researchInterests: 'What are your research interests?',
  workTypes: 'What type of work do you do?',
  responsePreferences: 'How would you like your reports written?',
}
const OTHER_LABEL = 'Please specify your occupation'

type QuestionsProps = {
  answers: Answers
  onChange: (answers: Answers) => void
  errors?: AnswerErrors
}

const labelClasses = 'block text-sm/6 font-medium text-gray-900 dark:text-gray-100'
const errorClasses = 'mt-2 text-sm text-red-600 dark:text-red-400'
const fieldClasses =
  'block w-full rounded-md bg-white px-3 py-1.5 text-base text-gray-900 outline-1 -outline-offset-1 outline-gray-300 placeholder:text-gray-400 focus:outline-2 focus:-outline-offset-2 focus:outline-indigo-600 sm:text-sm/6 dark:bg-white/5 dark:text-white dark:outline-white/10 dark:placeholder:text-gray-400 dark:focus:outline-indigo-500'

// anything but Other hides the box and clears it, so no stale text is kept
function withOccupation(answers: Answers, occupation: string): Answers {
  return { ...answers, occupation, occupationOther: occupation === OTHER ? answers.occupationOther : '' }
}

// kept in the list's order, whatever order they were ticked in
function withWorkType(answers: Answers, workType: string, checked: boolean): Answers {
  return {
    ...answers,
    workTypes: WORK_TYPES.filter((type) => (type === workType ? checked : answers.workTypes.includes(type))),
  }
}

export function GeminiNotice({ className = '' }: { className?: string }) {
  return (
    <p className={`flex gap-2 text-sm/5 text-gray-400 ${className}`}>
      <InformationCircleIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-indigo-400" />
      {GEMINI_NOTICE}
    </p>
  )
}

function OtherOccupation({ answers, onChange, errors = {} }: QuestionsProps) {
  return (
    <div>
      <label htmlFor="occupation-other" className={labelClasses}>
        {OTHER_LABEL}
      </label>
      <div className="mt-2">
        <input
          id="occupation-other"
          type="text"
          maxLength={MAX_OCCUPATION_OTHER_LENGTH}
          value={answers.occupationOther}
          onChange={(e) => onChange({ ...answers, occupationOther: e.target.value })}
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
  )
}

function PreferencesField({ answers, onChange, errors = {}, labelledBy }: QuestionsProps & { labelledBy?: string }) {
  return (
    <div>
      <textarea
        id="response-preferences"
        rows={labelledBy ? 5 : 3}
        maxLength={MAX_PREFERENCES_LENGTH}
        placeholder={PREFERENCES_PLACEHOLDER}
        value={answers.responsePreferences}
        onChange={(e) => onChange({ ...answers, responsePreferences: e.target.value })}
        aria-labelledby={labelledBy}
        aria-invalid={errors.responsePreferences ? true : undefined}
        aria-describedby={
          errors.responsePreferences ? 'response-preferences-count response-preferences-error' : 'response-preferences-count'
        }
        // one screen per question in sign-up gives it room enough, so no unthemed resize grip there
        className={`${fieldClasses} ${labelledBy ? 'resize-none' : 'resize-y'}`}
      />
      <p id="response-preferences-count" className="mt-1 text-right text-xs text-gray-500 tabular-nums dark:text-gray-400">
        {answers.responsePreferences.length}/{MAX_PREFERENCES_LENGTH}
      </p>
      {errors.responsePreferences && (
        <p id="response-preferences-error" className={errorClasses}>
          {errors.responsePreferences}
        </p>
      )}
    </div>
  )
}

/** Every question at once, as the profile page shows them. */
export default function ProfileQuestions({ answers, onChange, errors = {} }: QuestionsProps) {
  return (
    <div className="space-y-6">
      <p className="rounded-md bg-gray-50 px-3 py-2 text-sm text-gray-700 dark:bg-white/5 dark:text-gray-300">
        {GEMINI_NOTICE}
      </p>

      <div>
        <label htmlFor="occupation" className={labelClasses}>
          {QUESTION.occupation}
        </label>
        <div className="mt-2">
          <select
            id="occupation"
            value={answers.occupation}
            onChange={(e) => onChange(withOccupation(answers, e.target.value))}
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
            <OtherOccupation answers={answers} onChange={onChange} errors={errors} />
          </div>
        )}
      </div>

      <TagInput
        id="research-fields"
        label={QUESTION.researchFields}
        value={answers.researchFields}
        onChange={(researchFields) => onChange({ ...answers, researchFields })}
        options={RESEARCH_FIELDS}
        allowCustom={false}
      />

      <TagInput
        id="research-interests"
        label={QUESTION.researchInterests}
        value={answers.researchInterests}
        onChange={(researchInterests) => onChange({ ...answers, researchInterests })}
        options={INTEREST_SUGGESTIONS}
        allowCustom
        maxTags={MAX_INTERESTS}
        maxTagLength={MAX_INTEREST_LENGTH}
        error={errors.researchInterests}
      />

      <fieldset>
        <legend className={labelClasses}>{QUESTION.workTypes}</legend>
        <div className="mt-2 grid gap-2 sm:grid-cols-2">
          {WORK_TYPES.map((workType) => (
            <label key={workType} className="flex items-start gap-2 text-sm text-gray-900 dark:text-gray-100">
              <input
                type="checkbox"
                checked={answers.workTypes.includes(workType)}
                onChange={(e) => onChange(withWorkType(answers, workType, e.target.checked))}
                className="mt-0.5 size-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
              />
              {workType}
            </label>
          ))}
        </div>
      </fieldset>

      <div>
        <label htmlFor="response-preferences" className={labelClasses}>
          {QUESTION.responsePreferences}
        </label>
        <div className="mt-2">
          <PreferencesField answers={answers} onChange={onChange} errors={errors} />
        </div>
      </div>
    </div>
  )
}

// an answer tile: an outlined choice that fills indigo when chosen; the real input stays in the
// tab order (visually hidden), so arrow keys, Space and screen readers work as on any radio or checkbox
const tileClasses =
  'group flex cursor-pointer items-center gap-3 rounded-xl bg-white/[0.02] px-4 py-3 text-sm/5 text-gray-200 outline-1 -outline-offset-1 outline-white/15 transition-[background-color,outline-color] duration-150 hover:bg-white/5 hover:outline-white/30 has-checked:bg-indigo-500/12 has-checked:text-white has-checked:outline-indigo-400 has-focus-visible:outline-2 has-focus-visible:outline-indigo-400'

function RadioTile({ name, value, checked, onSelect }: { name: string; value: string; checked: boolean; onSelect: () => void }) {
  return (
    <label className={tileClasses}>
      <input type="radio" name={name} value={value} checked={checked} onChange={onSelect} className="sr-only" />
      <span
        aria-hidden="true"
        className="grid size-4 shrink-0 place-items-center rounded-full outline-1 outline-white/30 group-has-checked:outline-indigo-400"
      >
        <span className="size-2 scale-0 rounded-full bg-indigo-400 transition-transform duration-150 group-has-checked:scale-100" />
      </span>
      {value}
    </label>
  )
}

function CheckTile({ value, checked, onToggle }: { value: string; checked: boolean; onToggle: (checked: boolean) => void }) {
  return (
    <label className={tileClasses}>
      <input type="checkbox" checked={checked} onChange={(e) => onToggle(e.target.checked)} className="sr-only" />
      <span
        aria-hidden="true"
        className="grid size-4 shrink-0 place-items-center rounded outline-1 outline-white/30 group-has-checked:bg-indigo-500 group-has-checked:outline-indigo-400"
      >
        <CheckIcon className="size-3 scale-0 text-white transition-transform duration-150 group-has-checked:scale-100" />
      </span>
      {value}
    </label>
  )
}

type StepProps = QuestionsProps & {
  step: ProfileStep
  // the question is the screen's heading; sign-up moves focus to it when the screen changes
  headingRef?: Ref<HTMLHeadingElement>
}

/** One question as its own screen, for sign-up: the question as the heading, then its answer. */
export function QuestionStep({ step, answers, onChange, errors = {}, headingRef }: StepProps) {
  const headingId = `question-${step}`
  return (
    <div>
      <h2
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="text-2xl/8 font-semibold tracking-tight text-balance text-white focus:outline-none"
      >
        {QUESTION[step]}
      </h2>

      <div className="mt-6">
        {step === 'occupation' && (
          <div className="space-y-4">
            <div role="radiogroup" aria-labelledby={headingId} className="grid gap-2 sm:grid-cols-2">
              {OCCUPATIONS.map((occupation) => (
                <RadioTile
                  key={occupation}
                  name="occupation"
                  value={occupation}
                  checked={answers.occupation === occupation}
                  onSelect={() => onChange(withOccupation(answers, occupation))}
                />
              ))}
            </div>
            {answers.occupation === OTHER && (
              <div className="animate-reveal">
                <OtherOccupation answers={answers} onChange={onChange} errors={errors} />
              </div>
            )}
            {answers.occupation !== '' && (
              <button
                type="button"
                onClick={() => onChange(withOccupation(answers, ''))}
                className="text-sm font-medium text-gray-400 underline-offset-4 hover:text-white hover:underline focus-visible:rounded focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-400"
              >
                Clear my answer
              </button>
            )}
          </div>
        )}

        {step === 'researchFields' && (
          <TagInput
            id="research-fields"
            label="Search the research fields"
            value={answers.researchFields}
            onChange={(researchFields) => onChange({ ...answers, researchFields })}
            options={RESEARCH_FIELDS}
            allowCustom={false}
          />
        )}

        {step === 'researchInterests' && (
          <TagInput
            id="research-interests"
            label={`Pick a suggestion or type your own, up to ${MAX_INTERESTS}`}
            value={answers.researchInterests}
            onChange={(researchInterests) => onChange({ ...answers, researchInterests })}
            options={INTEREST_SUGGESTIONS}
            allowCustom
            maxTags={MAX_INTERESTS}
            maxTagLength={MAX_INTEREST_LENGTH}
            error={errors.researchInterests}
          />
        )}

        {step === 'workTypes' && (
          <div role="group" aria-labelledby={headingId} className="grid gap-2 sm:grid-cols-2">
            {WORK_TYPES.map((workType) => (
              <CheckTile
                key={workType}
                value={workType}
                checked={answers.workTypes.includes(workType)}
                onToggle={(checked) => onChange(withWorkType(answers, workType, checked))}
              />
            ))}
          </div>
        )}

        {step === 'responsePreferences' && (
          <PreferencesField answers={answers} onChange={onChange} errors={errors} labelledBy={headingId} />
        )}
      </div>
    </div>
  )
}
