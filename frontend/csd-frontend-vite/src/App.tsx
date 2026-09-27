import { BrowserRouter, Outlet, Route, Routes } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import { ToastProvider } from './components/Toasts'
import { TrackingProvider } from './tracking'
import Alerts from './pages/Alerts'
import CitationSources from './pages/CitationSources'
import Folders from './pages/Folders'

function AppLayout() {
  return (
    <div className="min-h-screen flex">
      <Sidebar />
      <section className="min-h-screen flex-1 bg-grey-950 mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <Outlet />
      </section>
    </div>
  )
}

function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <TrackingProvider>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/" element={<Folders />} />
              <Route path="/citation-sources" element={<CitationSources />} />
              <Route path="/alerts" element={<Alerts />} />
            </Route>
          </Routes>
        </TrackingProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}

export default App