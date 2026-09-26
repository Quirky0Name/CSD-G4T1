import { useState } from 'react'
import { Dialog, DialogBackdrop, DialogPanel, TransitionChild } from '@headlessui/react'
import {
  Bars3Icon,
  BellAlertIcon,
  FolderIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline'
import { NavLink } from 'react-router-dom'

const navigationItems = [
  { label: 'Folders', to: '/', icon: FolderIcon },
  { label: 'Alerts', to: '/alerts', icon: BellAlertIcon },
]

function classNames(...classes: Array<string | false | null | undefined>) {
  return classes.filter(Boolean).join(' ')
}

type SidebarContentProps = {
  onNavigate?: () => void
}

function SidebarContent({ onNavigate }: SidebarContentProps) {
  return (
    <div className="flex grow flex-col gap-y-5 overflow-y-auto border-r border-white/10 bg-gray-900 px-6 pb-4 text-gray-100">
      <div className="flex h-16 shrink-0 items-center">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.2em] text-gray-400">
            Research desk
          </p>
          <p className="mt-1 text-lg font-semibold text-white">Library</p>
        </div>
      </div>

      <nav aria-label="Main navigation" className="flex flex-1 flex-col">
        <ul role="list" className="-mx-2 space-y-1">
          {navigationItems.map((item) => (
            <li key={item.to}>
              <NavLink
                to={item.to}
                end={item.to === '/'}
                onClick={onNavigate}
                className={({ isActive }) =>
                  classNames(
                    isActive
                      ? 'bg-white/5 text-white'
                      : 'text-gray-400 hover:bg-white/5 hover:text-white',
                    'group flex gap-x-3 rounded-md p-2 text-sm/6 font-semibold',
                  )
                }
              >
                {({ isActive }) => {
                  const Icon = item.icon

                  return (
                    <>
                      <Icon
                        aria-hidden="true"
                        className={classNames(
                          isActive
                            ? 'text-white'
                            : 'text-gray-500 group-hover:text-white',
                          'size-6 shrink-0',
                        )}
                      />
                      {item.label}
                    </>
                  )
                }}
              </NavLink>
            </li>
          ))}
        </ul>

        <div className="mt-auto border-t border-white/10 pt-4">
          <div className="flex items-center gap-x-3 px-2 py-2 text-sm font-semibold text-white">
            <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-white/5 text-sm text-gray-300">
              U
            </span>
            <span>Your account</span>
          </div>
        </div>
      </nav>
    </div>
  )
}

function Sidebar() {
  const [sidebarOpen, setSidebarOpen] = useState(false)

  return (
    <>
      <div className="sticky top-0 z-40 flex items-center gap-x-6 bg-gray-900 px-4 py-4 shadow-sm lg:hidden">
        <button
          type="button"
          onClick={() => setSidebarOpen(true)}
          className="-m-2.5 p-2.5 text-gray-400 hover:text-white"
        >
          <span className="sr-only">Open sidebar</span>
          <Bars3Icon aria-hidden="true" className="size-6" />
        </button>
        <div className="flex-1 text-sm/6 font-semibold text-white">Library</div>
      </div>

      <Dialog open={sidebarOpen} onClose={setSidebarOpen} className="relative z-50 lg:hidden">
        <DialogBackdrop className="fixed inset-0 bg-gray-900/80 transition-opacity" />
        <div className="fixed inset-0 flex">
          <DialogPanel className="relative mr-16 flex w-full max-w-xs flex-1">
            <TransitionChild>
              <div className="absolute top-0 left-full flex w-16 justify-center pt-5">
                <button
                  type="button"
                  onClick={() => setSidebarOpen(false)}
                  className="-m-2.5 p-2.5 text-white"
                >
                  <span className="sr-only">Close sidebar</span>
                  <XMarkIcon aria-hidden="true" className="size-6" />
                </button>
              </div>
            </TransitionChild>
            <SidebarContent onNavigate={() => setSidebarOpen(false)} />
          </DialogPanel>
        </div>
      </Dialog>

      <div className="hidden lg:fixed lg:inset-y-0 lg:z-50 lg:flex lg:w-72 lg:flex-col">
        <SidebarContent />
      </div>
    </>
  )
}

export default Sidebar
