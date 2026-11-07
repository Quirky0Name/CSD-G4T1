import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/react'
import { BellAlertIcon, DocumentTextIcon, FolderIcon } from '@heroicons/react/24/outline'
import { NavLink, useLocation } from 'react-router-dom'
import { useAuth } from '../auth'

const navigationItems = [
  { label: 'Projects', to: '/', icon: FolderIcon },
  { label: 'Alerts', to: '/alerts', icon: BellAlertIcon },
  { label: 'Reports', to: '/reports', icon: DocumentTextIcon },
]

// A rail beside the page from sm up, and a bar above it on a phone
function Sidebar() {
  const { session, logout } = useAuth()
  const email = session?.user.email
  const { pathname } = useLocation()
  // /projects is User Management's, so a project's page lives under /project
  const inProject = pathname === '/project' || pathname.startsWith('/project/') || pathname.startsWith('/papers/')

  return (
    <nav
      aria-label="Main navigation"
      className="sticky top-0 z-40 flex shrink-0 items-center gap-2 border-b border-white/10 bg-gray-900 px-2 py-1.5 sm:h-screen sm:w-18 sm:flex-col sm:border-r sm:border-b-0 sm:px-1.5 sm:py-3"
    >
      <ul role="list" className="flex flex-1 gap-1 sm:w-full sm:flex-col">
        {navigationItems.map(({ label, to, icon: Icon }) => (
          <li key={to} className="flex-1 sm:flex-none">
            <NavLink
              to={to}
              end={to === '/'}
              className={({ isActive }) =>
                `flex flex-col items-center gap-0.5 rounded-md py-1.5 text-[11px]/4 font-medium focus-visible:outline-2 focus-visible:outline-indigo-500 ${
                  isActive || (to === '/' && inProject) ? 'bg-white/10 text-white' : 'text-gray-400 hover:bg-white/5 hover:text-white'
                }`
              }
            >
              <Icon aria-hidden="true" className="size-5" />
              {label}
            </NavLink>
          </li>
        ))}
      </ul>

      <Menu>
        <MenuButton
          title={email}
          className="flex size-9 shrink-0 items-center justify-center rounded-full bg-white/10 text-gray-200 hover:bg-white/20 hover:text-white focus-visible:outline-2 focus-visible:outline-indigo-500"
        >
          <span className="sr-only">Account</span>
          <span aria-hidden="true" className="text-sm/5 font-semibold">
            {(email?.[0] ?? 'U').toUpperCase()}
          </span>
        </MenuButton>
        <MenuItems
          anchor={{ to: 'right end', gap: 8, padding: 8 }}
          className="z-50 w-60 rounded-md bg-gray-800 py-1 shadow-lg outline outline-white/10 focus:outline-none"
        >
          <p className="truncate border-b border-white/10 px-3 py-2 text-xs/5 text-gray-400">{email ?? 'Your account'}</p>
          <MenuItem>
            <button
              type="button"
              onClick={logout}
              className="block w-full px-3 py-2 text-left text-white data-focus:bg-white/5"
            >
              <span className="text-sm/5">Sign out</span>
            </button>
          </MenuItem>
        </MenuItems>
      </Menu>
    </nav>
  )
}

export default Sidebar
