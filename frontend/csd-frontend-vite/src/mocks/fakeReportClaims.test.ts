// The fixture against the example files it quotes (storage/src/main/resources/dev/fake-report/, read only):
// every verbatim quote is on the page it names, as pdf.js reads that page, so the report page's highlight
// lands. This is what stops the fixture drifting from the PDFs.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs'
import { describe, expect, it } from 'vitest'
import { findQuote } from '../highlight/normalise'
import { joinPages, pageText, type PageText, type TextPiece } from '../highlight/pageText'
import { CURRENT_COPY, FAKE_REPORT_CLAIMS, NEW_VERSION, type MockPassage } from './fakeReportClaims'

const EXAMPLE = fileURLToPath(new URL('../../../../storage/src/main/resources/dev/fake-report/', import.meta.url))

type ExampleReport = {
  alerts: { notice_doi: string | null; description: string }[]
  documents: { doi: string; text: string | null; pdf_file: string | null }[]
}
const report = JSON.parse(readFileSync(`${EXAMPLE}report.json`, 'utf8')) as ExampleReport

async function pages(file: string): Promise<PageText[]> {
  const data = new Uint8Array(readFileSync(`${EXAMPLE}${file}`))
  const pdf = await getDocument({ data }).promise
  const out: PageText[] = []
  for (let n = 1; n <= pdf.numPages; n++) {
    const content = await (await pdf.getPage(n)).getTextContent()
    out.push(pageText(content.items as TextPiece[]))
  }
  return out
}

function pdfFile(passage: MockPassage): string {
  if (passage.target === 'draft') return 'draft.pdf'
  if (passage.target === 'paper') return 'paper.pdf'
  const doc = report.documents.find((d) => d.doi === passage.document_doi)
  if (!doc?.pdf_file) throw new Error(`no PDF for ${passage.document_doi}`)
  return doc.pdf_file
}

const passages = FAKE_REPORT_CLAIMS.flatMap((claim) => claim.passages.map((passage) => ({ claim: claim.id, passage })))
const onPages = passages.filter(({ passage }) => passage.verified === 'verbatim' && passage.page !== null)
const inText = passages.filter(({ passage }) => passage.verified === 'verbatim' && passage.page === null)

describe('the fake report fixture', () => {
  it.each(onPages)('$claim: the quote is on its page', async ({ passage }) => {
    const all = await pages(pdfFile(passage))
    const page = passage.page as number
    const { text } = joinPages(all[page - 1], all[page] ?? null)
    expect(findQuote(passage.quote, text), `"${passage.quote}" on page ${page}`).not.toBeNull()
  })

  it.each(inText)('$claim: the quote is in its notice or alert text', ({ passage }) => {
    const text =
      passage.target === 'alert'
        ? report.alerts.find((a) => a.notice_doi === passage.alert_notice_doi)?.description
        : report.documents.find((d) => d.doi === passage.document_doi)?.text
    expect(text, 'the passage names a text').toBeTruthy()
    expect(findQuote(passage.quote, text as string)).not.toBeNull()
  })

  it("has the not_found quote nowhere in the draft", async () => {
    const draft = await pages('draft.pdf')
    const whole = draft.map((p) => p.text).join('\n')
    for (const { passage } of passages.filter(({ passage }) => passage.verified === 'not_found')) {
      expect(findQuote(passage.quote, whole)).toBeNull()
    }
  })

  it('names only documents the example has', () => {
    const dois = new Set(report.documents.map((d) => d.doi))
    for (const { passage } of passages.filter(({ passage }) => passage.target === 'document')) {
      expect(dois.has(passage.document_doi as string)).toBe(true)
    }
    expect(dois.has(NEW_VERSION) && dois.has(CURRENT_COPY)).toBe(true)
  })
})
