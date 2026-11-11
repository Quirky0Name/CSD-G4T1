// The questionnaire's lists and limits (docs/CONTRACTS.md, the profile body). The stored value is
// the text shown, so Research Evaluation can put it in the prompt as it is. User Management checks
// the same lists from users/src/main/resources/profile/options.json; options.test.ts keeps the two
// equal, since the frontend and User Management build from separate Docker contexts.

export const OTHER = 'Other'

export const OCCUPATIONS = [
  'Undergraduate student',
  "Master's student",
  'PhD student',
  'Postdoctoral researcher',
  'Faculty or principal investigator',
  'Research staff',
  'Clinician',
  'Industry researcher',
  'Other',
] as const

// the 26 OpenAlex fields (https://api.openalex.org/fields, fetched 2026-11-09, data CC0), sorted
export const RESEARCH_FIELDS = [
  'Agricultural and Biological Sciences',
  'Arts and Humanities',
  'Biochemistry, Genetics and Molecular Biology',
  'Business, Management and Accounting',
  'Chemical Engineering',
  'Chemistry',
  'Computer Science',
  'Decision Sciences',
  'Dentistry',
  'Earth and Planetary Sciences',
  'Economics, Econometrics and Finance',
  'Energy',
  'Engineering',
  'Environmental Science',
  'Health Professions',
  'Immunology and Microbiology',
  'Materials Science',
  'Mathematics',
  'Medicine',
  'Neuroscience',
  'Nursing',
  'Pharmacology, Toxicology and Pharmaceutics',
  'Physics and Astronomy',
  'Psychology',
  'Social Sciences',
  'Veterinary',
] as const

export const WORK_TYPES = [
  'Experimental or laboratory',
  'Clinical',
  'Computational or modelling',
  'Theoretical',
  'Survey or qualitative',
  'Systematic review or meta-analysis',
  'Data or software',
  'Other',
] as const

export const MAX_INTERESTS = 5
export const MAX_INTEREST_LENGTH = 60
export const MAX_OCCUPATION_OTHER_LENGTH = 60
// UTF-16 code units, what both a textarea's maxLength and User Management's String.length() count
export const MAX_PREFERENCES_LENGTH = 500

export const PREFERENCES_PLACEHOLDER = 'e.g. Keep it brief and tell me what to do first.'
export const GEMINI_NOTICE =
  'Your answers are sent to Gemini to tailor the explanation and recommendation in your reports.'
