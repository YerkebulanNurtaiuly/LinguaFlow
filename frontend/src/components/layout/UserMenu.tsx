import { LogOut } from 'lucide-react'
import { useState } from 'react'
import { useAuth } from '@/auth/authContext'
import { Button } from '@/components/ui'
import { cn } from '@/lib/cn'

/** The logged-in user's name and a log-out button, for the sidebar and mobile drawer. */
export function UserMenu({ className }: { className?: string }) {
  const { user, logout } = useAuth()
  const [loggingOut, setLoggingOut] = useState(false)

  const name = user?.displayName?.trim() || user?.email

  return (
    <div className={cn('flex items-center gap-2', className)}>
      <div className="min-w-0 flex-1">
        {name ? (
          <>
            <p className="truncate text-sm font-medium text-fg">{name}</p>
            {user?.displayName && (
              <p className="truncate text-xs text-fg-subtle">{user.email}</p>
            )}
          </>
        ) : (
          <p className="text-sm text-fg-subtle">Signed in</p>
        )}
      </div>
      <Button
        variant="ghost"
        size="sm"
        loading={loggingOut}
        onClick={async () => {
          setLoggingOut(true)
          await logout()
        }}
      >
        {!loggingOut && <LogOut className="size-4" aria-hidden="true" />}
        Log out
      </Button>
    </div>
  )
}
