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
import type { MascotPose } from './Mascot'
import { PoseBadge } from './AuthShell'

// Sign-up's scene: the penguin acts out each step (a wave hello, a think over the open questions,
// a laptop for the work ones, a cheer at the review) while a badge at its shoulder names the
// step and pops in when the step changes.

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

// eslint-disable-next-line react-refresh/only-export-components -- a table read beside the badge, not a component
export const SIGNUP_POSE: Record<SignupStage, MascotPose> = {
  account: 'wave',
  occupation: 'stand',
  researchFields: 'read',
  researchInterests: 'think',
  workTypes: 'laptop',
  responsePreferences: 'happy',
  review: 'cheer',
}

export function SignupBadge({ stage }: { stage: SignupStage }) {
  const Badge = BADGE[stage]
  return (
    <PoseBadge stepKey={stage}>
      <Badge aria-hidden="true" className="size-4 sm:size-6 md:size-7" />
    </PoseBadge>
  )
}
