import { BrowserRouter, Outlet, Route, Routes } from 'react-router-dom'
import Sidebar from './components/Sidebar'
import Alerts from './pages/Alerts'
import CitationSources from './pages/CitationSources'
import Folders from './pages/Folders'

function AppLayout() {
  return (
    <div className="min-h-screen flex">
      <Sidebar />
      <section className="min-h-screen flex-1 bg-gray-950 lg:pl-72">
        <Outlet />
      </section>
    </div>
  )
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/" element={<Folders />} />
          <Route path="/citation-sources" element={<CitationSources />} />
          <Route path="/alerts" element={<Alerts />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

export default App
