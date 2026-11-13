/**
 * The daily review goal as a ring (FE-PRG-01): one hue on a muted track, the count in
 * text beside it, so the figure never depends on the colour. A single value needs no
 * legend or hover; the meter role carries it for screen readers.
 */
export function GoalRing({ done, goal }: { done: number; goal: number }) {
  const size = 96
  const stroke = 10
  const radius = (size - stroke) / 2
  const circumference = 2 * Math.PI * radius
  const share = goal > 0 ? Math.min(done / goal, 1) : 0
  return (
    <div
      role="meter"
      aria-label="Daily review goal"
      aria-valuemin={0}
      aria-valuemax={goal}
      aria-valuenow={Math.min(done, goal)}
      aria-valuetext={`${done} of ${goal} reviews`}
      className="relative size-24 shrink-0"
    >
      <svg
        viewBox={`0 0 ${size} ${size}`}
        className="size-full -rotate-90"
        aria-hidden="true"
      >
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          className="stroke-surface-muted"
        />
        {share > 0 && (
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={`${share * circumference} ${circumference}`}
            className="stroke-primary transition-[stroke-dasharray] motion-reduce:transition-none"
          />
        )}
      </svg>
      <span className="absolute inset-0 flex items-center justify-center text-lg font-semibold text-fg">
        {Math.round(share * 100)}%
      </span>
    </div>
  )
}
