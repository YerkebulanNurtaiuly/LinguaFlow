import {
  BookOpen,
  CalendarDays,
  Dumbbell,
  GraduationCap,
  Layers,
  LayoutDashboard,
  Settings,
  TrendingUp,
  type LucideIcon,
} from 'lucide-react'

export interface NavItem {
  path: string
  label: string
  icon: LucideIcon
  /** One-line summary, shown on the section's page until it is built */
  description: string
}

/** Main navigation of the app shell, in display order. */
export const NAV_ITEMS: NavItem[] = [
  {
    path: '/dashboard',
    label: 'Dashboard',
    icon: LayoutDashboard,
    description: 'Due reviews, daily goal, streak and quick start.',
  },
  {
    path: '/vocabulary',
    label: 'Vocabulary',
    icon: BookOpen,
    description: 'Your words with translations, articles and examples.',
  },
  {
    path: '/grammar',
    label: 'Grammar',
    icon: GraduationCap,
    description:
      'The grammar topics you have studied and how well you know them.',
  },
  {
    path: '/sessions',
    label: 'Study sessions',
    icon: CalendarDays,
    description: 'Your lessons and the material you learned in each.',
  },
  {
    path: '/flashcards',
    label: 'Flashcards',
    icon: Layers,
    description: 'Review due cards with spaced repetition.',
  },
  {
    path: '/practice',
    label: 'Practice',
    icon: Dumbbell,
    description: 'Current lesson, mixed practice and full revision.',
  },
  {
    path: '/progress',
    label: 'Progress',
    icon: TrendingUp,
    description: 'Statistics, weak points and frequent mistakes.',
  },
  {
    path: '/settings',
    label: 'Settings',
    icon: Settings,
    description: 'Profile, languages and learning settings.',
  },
]
