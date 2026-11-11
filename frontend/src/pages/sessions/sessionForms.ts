import { z } from 'zod'

/** Title, date and notes of a lesson; mirrors the backend limits (SES-01). */
export const sessionDetailsSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, 'Enter a title, e.g. Lesson 5.')
    .max(200, 'The title can be at most 200 characters.'),
  studiedOn: z.iso.date('Enter a valid date.'),
  notes: z
    .string()
    .max(5000, 'Notes can be at most 5000 characters.')
    .transform((value) => value.trimEnd()),
})
