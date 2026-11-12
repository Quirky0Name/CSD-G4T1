// The claims of dev mode's fake report, in the shape of docs/CONTRACTS.md's "Claim" on
// feat/claim-passages-backend, except that a passage names its document or alert by DOI: a fake report's
// rows get new ids each time it is added, so withMockClaims (fakeReport.ts) swaps each DOI for this
// report's id. Every quote and page is checked against the example PDFs and notice texts by
// fakeReportClaims.test.ts; change one only with the example files beside it.
import type { ClaimKind, PassageTarget, PassageVerified } from '../api'

export type MockPassage = {
  target: PassageTarget | null
  // the report document with this DOI, when target is 'document'
  document_doi?: string
  // the report alert with this notice_doi, when target is 'alert'
  alert_notice_doi?: string
  quote: string
  page: number | null
  verified: PassageVerified
}

export type MockClaim = {
  id: string
  kind: ClaimKind
  text: string
  passages: MockPassage[]
}

export const EOC_NOTICE = '10.9999/fake.2019.011e'
export const RETRACTION_NOTICE = '10.9999/fake.2019.011r'
export const NEW_VERSION = '10.9999/fake.2019.011v2'
export const CURRENT_COPY = '10.9999/fake.2019.011'

const OLD_SLOPE = 'slope: 0.45 points per 10 mg (95% CI 0.30 to 0.60)'
const NEW_SLOPE = 'slope: 0.28 points per 10 mg (95% CI 0.15 to 0.41)'

export const FAKE_REPORT_CLAIMS: MockClaim[] = [
  {
    id: 'change-0',
    kind: 'change',
    text: "The editors issued an expression of concern about how the paper's dose-response analysis was calculated.",
    passages: [
      {
        target: 'document',
        document_doi: EOC_NOTICE,
        quote: 'The editors have received concerns about the calculation of the dose-response analysis in this article and are investigating.',
        page: null,
        verified: 'verbatim',
      },
      { target: 'paper', quote: OLD_SLOPE, page: 3, verified: 'verbatim' },
    ],
  },
  {
    id: 'change-1',
    kind: 'change',
    text: 'The paper was retracted and replaced by a corrected version.',
    passages: [
      {
        target: 'document',
        document_doi: RETRACTION_NOTICE,
        quote: 'this article is retracted and replaced by a corrected version',
        page: null,
        verified: 'verbatim',
      },
      {
        target: 'document',
        document_doi: NEW_VERSION,
        quote: 'This corrected version replaces the retracted article',
        page: 1,
        verified: 'verbatim',
      },
    ],
  },
  {
    id: 'change-2',
    kind: 'change',
    text: 'The dose-response slope was corrected from 0.45 to 0.28 points per 10 mg (95% CI 0.15 to 0.41).',
    passages: [
      {
        target: 'document',
        document_doi: RETRACTION_NOTICE,
        quote: 'it was reported as 0.45 points per 10 mg and the correct value is 0.28 points per 10 mg',
        page: null,
        verified: 'verbatim',
      },
      { target: 'document', document_doi: NEW_VERSION, quote: NEW_SLOPE, page: 3, verified: 'verbatim' },
    ],
  },
  {
    id: 'change-3',
    kind: 'change',
    text: 'The copy at the original DOI still shows the old slope with no retraction mark.',
    passages: [{ target: 'document', document_doi: CURRENT_COPY, quote: OLD_SLOPE, page: 3, verified: 'verbatim' }],
  },
  {
    id: 'change-4',
    kind: 'change',
    text: 'The finding that iron reduces fatigue is unchanged.',
    passages: [
      {
        target: 'document',
        document_doi: RETRACTION_NOTICE,
        quote: 'The finding that iron supplementation reduces fatigue is unchanged.',
        page: null,
        verified: 'verbatim',
      },
    ],
  },
  {
    id: 'change-5',
    kind: 'change',
    text: 'The publisher flagged the paper with an expression of concern.',
    passages: [
      {
        target: 'alert',
        alert_notice_doi: EOC_NOTICE,
        quote: 'The publisher issued an expression of concern about this paper',
        page: null,
        verified: 'verbatim',
      },
    ],
  },
  {
    id: 'impact-0',
    kind: 'impact',
    text: "Your Discussion compares your slope of 0.41 with the paper's 0.45 per 10 mg, and treats the closeness as support for your design.",
    passages: [
      {
        target: 'draft',
        quote: 'slope of 0.41 fatigue points per 10 mg of iron is close to that of Park and Singh [7] (0.45 per 10 mg), which supports the validity of our design',
        page: 2,
        verified: 'verbatim',
      },
      { target: 'paper', quote: OLD_SLOPE, page: 3, verified: 'verbatim' },
    ],
  },
  {
    id: 'impact-1',
    kind: 'impact',
    text: 'Your draft lists the paper as reference [7].',
    passages: [
      {
        target: 'draft',
        quote: '[7] Park, J., Singh, R. (2019). Dose-response of iron supplementation on fatigue. J. Fake Nutrition.',
        page: 3,
        verified: 'verbatim',
      },
    ],
  },
  // state coverage: a claim with nothing to quote
  {
    id: 'impact-2',
    kind: 'impact',
    text: "Your own methods and estimate don't depend on the paper.",
    passages: [],
  },
  // state coverage: a quote that isn't in the draft
  {
    id: 'impact-3',
    kind: 'impact',
    text: "Your Abstract repeats the paper's effect size.",
    passages: [
      { target: 'draft', quote: 'consistent with the 0.45 slope reported previously', page: null, verified: 'not_found' },
    ],
  },
  {
    id: 'action-0',
    kind: 'action',
    text: 'Replace 0.45 with the corrected 0.28 per 10 mg (95% CI 0.15 to 0.41) and cite the corrected version.',
    passages: [
      {
        target: 'draft',
        quote: '(0.45 per 10 mg), which supports the validity of our design',
        page: 2,
        verified: 'verbatim',
      },
      { target: 'document', document_doi: NEW_VERSION, quote: NEW_SLOPE, page: 3, verified: 'verbatim' },
    ],
  },
  {
    id: 'action-1',
    kind: 'action',
    text: 'Update reference [7] to point to the corrected version.',
    passages: [{ target: 'draft', quote: '[7] Park, J., Singh, R. (2019)', page: 3, verified: 'verbatim' }],
  },
  // state coverage: a source with no readable text
  {
    id: 'action-2',
    kind: 'action',
    text: "Check the corrected version's supplementary data before resubmitting.",
    passages: [{ target: 'paper', quote: 'Supplementary Table S2', page: null, verified: 'no_text' }],
  },
]
