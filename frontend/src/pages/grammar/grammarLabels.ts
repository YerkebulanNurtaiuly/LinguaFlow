import type { CefrLevel, KnowledgeStatus } from '@/grammar/grammarApi'

export const LEVEL_NAMES: Record<CefrLevel, string> = {
  A1: 'Beginner',
  A2: 'Elementary',
  B1: 'Intermediate',
  B2: 'Upper intermediate',
  C1: 'Advanced',
  C2: 'Proficient',
}

export const STATUS_LABELS: Record<KnowledgeStatus, string> = {
  NEW: 'New',
  LEARNING: 'Learning',
  LEARNED: 'Learned',
  MASTERED: 'Mastered',
}

/** Statuses a topic may start with; MASTERED is earned. */
export const STARTING_STATUSES = ['NEW', 'LEARNING', 'LEARNED'] as const

export const topicCount = (count: number) =>
  `${count.toLocaleString()} ${count === 1 ? 'topic' : 'topics'}`
