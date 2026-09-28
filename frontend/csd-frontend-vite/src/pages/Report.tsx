import AlertList from "../components/AlertList"
import ReportList from "../components/ReportList"

function Report() {
  return (
    <main className="min-h-screen px-4 py-10 text-gray-100 sm:px-6 lg:px-8">
      <h1 className="text-3xl font-bold text-white">Alerts</h1>
      <AlertList />
      <ReportList />
    </main>
  )
}

export default Report
