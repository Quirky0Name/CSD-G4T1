import AlertList from "../components/AlertList"
import ReportList from "../components/ReportList"

function Alerts() {
  return (
    <main className="min-h-screen px-4 py-10 text-gray-100 sm:px-6 lg:px-8">
      <h1 className="text-3xl font-bold text-white">Alerts</h1>
      <div className="mt-8 grid grid-cols-1 gap-8 lg:grid-cols-2">
        <section className="min-w-0">
          <AlertList />
        </section>
        <section className="min-w-0">
          <ReportList />
        </section>
      </div>
    </main>
  )
}

export default Alerts