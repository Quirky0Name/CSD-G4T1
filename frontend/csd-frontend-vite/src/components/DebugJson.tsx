// TEMPORARY: shows a raw API response on the page. Delete this file and its uses
// (AlertList.tsx, ReportList.tsx) once the response shapes are understood.
export default function DebugJson({ label, data }: { label: string; data: unknown }) {
  return (
    <div className="mb-5 rounded-md bg-red-500 p-3 text-black">
      <p className="text-xs font-bold uppercase">TEMPORARY DEBUG, REMOVE BEFORE MERGING: {label}</p>
      <pre className="mt-2 max-h-96 overflow-auto text-xs whitespace-pre-wrap">{JSON.stringify(data, null, 2)}</pre>
    </div>
  )
}
