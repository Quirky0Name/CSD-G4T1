import {
  AcademicCapIcon,
  BeakerIcon,
  BriefcaseIcon,
  ChatBubbleLeftEllipsisIcon,
  CheckBadgeIcon,
  LightBulbIcon,
  UserPlusIcon,
} from '@heroicons/react/24/outline'
import type { ProfileStep } from '../profile/questionSteps'

// Sign-up's scene: a researcher pointing at a paper they cite, one of its lines flagged, as the app
// flags a change. The badge on the paper names the current step and pops in when the step changes.

export type SignupStage = 'account' | ProfileStep | 'review'

const BADGE: Record<SignupStage, typeof UserPlusIcon> = {
  account: UserPlusIcon,
  occupation: BriefcaseIcon,
  researchFields: AcademicCapIcon,
  researchInterests: LightBulbIcon,
  workTypes: BeakerIcon,
  responsePreferences: ChatBubbleLeftEllipsisIcon,
  review: CheckBadgeIcon,
}

const SKIN = '#e7b68c'
const HAIR = '#4a2f22'
const INDIGO = '#6366f1'
const SHEET = '#c7d2fe'

export default function SignupIllustration({ stage }: { stage: SignupStage }) {
  const Badge = BADGE[stage]
  return (
    <div className="relative mx-auto aspect-[6/5] w-full max-w-sm">
      <svg viewBox="0 0 360 300" aria-hidden="true" className="size-full">
        <circle cx="186" cy="140" r="120" fill="#ffffff" fillOpacity="0.04" />
        <circle cx="70" cy="70" r="7" fill="#ffffff" fillOpacity="0.07" />
        <circle cx="314" cy="208" r="10" fill="#ffffff" fillOpacity="0.06" />
        <circle cx="300" cy="48" r="5" fill={INDIGO} fillOpacity="0.5" />

        {/* the cited paper, one line flagged */}
        <g transform="rotate(-4 230 140)">
          <rect x="172" y="50" width="120" height="172" rx="10" fill={SHEET} />
          <path d="M268 50h14a10 10 0 0 1 10 10v14z" fill="#a5b4fc" />
          <rect x="190" y="74" width="62" height="9" rx="4.5" fill={INDIGO} />
          <rect x="190" y="98" width="84" height="6" rx="3" fill="#818cf8" fillOpacity="0.55" />
          <rect x="190" y="112" width="76" height="6" rx="3" fill="#818cf8" fillOpacity="0.55" />
          <rect x="184" y="123" width="96" height="16" rx="5" fill="#f59e0b" fillOpacity="0.9" />
          <rect x="190" y="128" width="70" height="6" rx="3" fill="#78350f" fillOpacity="0.55" />
          <rect x="190" y="148" width="84" height="6" rx="3" fill="#818cf8" fillOpacity="0.55" />
          <rect x="190" y="162" width="60" height="6" rx="3" fill="#818cf8" fillOpacity="0.55" />
          <rect x="190" y="176" width="80" height="6" rx="3" fill="#818cf8" fillOpacity="0.55" />
          <rect x="190" y="196" width="40" height="6" rx="3" fill="#818cf8" fillOpacity="0.35" />
        </g>

        <line x1="40" y1="262" x2="320" y2="262" stroke="#ffffff" strokeOpacity="0.18" strokeWidth="2" strokeLinecap="round" />

        {/* the researcher */}
        <rect x="90" y="186" width="13" height="72" rx="6.5" fill="#4b5563" />
        <rect x="107" y="186" width="13" height="72" rx="6.5" fill="#374151" />
        <ellipse cx="94" cy="259" rx="11" ry="5" fill="#e5e7eb" />
        <ellipse cx="117" cy="259" rx="11" ry="5" fill="#e5e7eb" />
        <rect x="80" y="116" width="50" height="84" rx="20" fill={INDIGO} />
        <path d="M84 136 Q70 160 80 186" stroke="#4f46e5" strokeWidth="12" strokeLinecap="round" fill="none" />
        <rect x="62" y="166" width="30" height="38" rx="4" fill="#e5e7eb" transform="rotate(-8 77 185)" />
        <rect x="68" y="176" width="16" height="3" rx="1.5" fill="#9ca3af" transform="rotate(-8 77 185)" />
        <rect x="68" y="184" width="12" height="3" rx="1.5" fill="#9ca3af" transform="rotate(-8 77 185)" />
        <circle cx="82" cy="186" r="6" fill={SKIN} />
        <path d="M124 132 L166 104" stroke={INDIGO} strokeWidth="12" strokeLinecap="round" />
        <circle cx="170" cy="101" r="6.5" fill={SKIN} />
        <rect x="99" y="102" width="12" height="16" rx="5" fill={SKIN} />
        <circle cx="105" cy="92" r="17" fill={SKIN} />
        <path d="M88 92c0-14 8-22 18-22s17 7 17 16c-6-4-14-5-21-2-5 2-9 5-14 8z" fill={HAIR} />
        <circle cx="122" cy="76" r="7" fill={HAIR} />
      </svg>

      {/* keyed by stage, so the badge pops in again on each step */}
      <span
        key={stage}
        className="animate-badge-pop absolute top-[11%] right-[13%] grid size-9 place-items-center rounded-full bg-indigo-500 text-white shadow-[0_10px_24px_-8px_rgba(99,102,241,0.7)] ring-[3px] ring-gray-900 sm:size-14 sm:ring-4"
      >
        <Badge aria-hidden="true" className="size-5 sm:size-7" />
      </span>
    </div>
  )
}
