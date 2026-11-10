import type { CefrLevel } from '@/api/types'
import type { RadioCardOption } from '@/components/ui'

/** "Not sure" sends no level; the backend stores none. */
export type LevelChoice = CefrLevel | 'unsure'

export const LEVEL_OPTIONS: readonly RadioCardOption<LevelChoice>[] = [
  {
    value: 'A1',
    label: 'A1 · Beginner',
    description: 'I know a few words and simple phrases.',
  },
  {
    value: 'A2',
    label: 'A2 · Elementary',
    description: 'I can handle short, everyday exchanges.',
  },
  {
    value: 'B1',
    label: 'B1 · Intermediate',
    description: 'I can get by when travelling and talk about familiar topics.',
  },
  {
    value: 'B2',
    label: 'B2 · Upper intermediate',
    description: 'I can follow most conversations and explain my views.',
  },
  {
    value: 'C1',
    label: 'C1 · Advanced',
    description: 'I express myself fluently, also at work or university.',
  },
  {
    value: 'C2',
    label: 'C2 · Proficient',
    description: 'I understand virtually everything I hear or read.',
  },
  {
    value: 'unsure',
    label: "I'm not sure",
    description: 'Fine too. You can set it later in Settings.',
  },
]

export type DailyGoal = '20' | '50' | '100' | '200'

export const DEFAULT_DAILY_GOAL: DailyGoal = '50'

export const DAILY_GOAL_OPTIONS: readonly RadioCardOption<DailyGoal>[] = [
  {
    value: '20',
    label: 'Casual · 20 reviews a day',
    description: 'A few minutes, easy to keep up.',
  },
  {
    value: '50',
    label: 'Regular · 50 reviews a day',
    description: 'A steady pace for most learners.',
  },
  {
    value: '100',
    label: 'Serious · 100 reviews a day',
    description: 'Faster progress with a daily study habit.',
  },
  {
    value: '200',
    label: 'Intense · 200 reviews a day',
    description: 'For an exam or a move abroad.',
  },
]
