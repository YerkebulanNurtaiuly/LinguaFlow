import { BookOpen } from 'lucide-react'
import { useState } from 'react'
import {
  Alert,
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CefrBadge,
  ConfirmDialog,
  EmptyState,
  Field,
  Input,
  Modal,
  Select,
  SkeletonText,
  StatusBadge,
  Table,
  TableCaption,
  TBody,
  Td,
  Th,
  THead,
  Tr,
  Tabs,
  Textarea,
  ThemeToggle,
  useToast,
} from '@/components/ui'

/** Dev-only overview of every design-system component (/design-system). */
export function DesignSystemPage() {
  const { toast } = useToast()
  const [modalOpen, setModalOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)

  return (
    <div className="max-w-4xl space-y-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">Design system</h1>
        <ThemeToggle />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Buttons</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Button>Primary</Button>
          <Button variant="secondary">Secondary</Button>
          <Button variant="ghost">Ghost</Button>
          <Button variant="danger">Danger</Button>
          <Button loading>Saving</Button>
          <Button size="sm">Small</Button>
          <Button size="lg">Large</Button>
          <Button disabled>Disabled</Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Alerts</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <Alert variant="danger">Wrong email or password.</Alert>
          <Alert variant="success" title="Account created">
            Check your inbox for the verification link.
          </Alert>
          <Alert>Your session has ended. Log in again.</Alert>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Form controls</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2">
          <Field label="Word" hint="With article, e.g. der Hund" required>
            <Input placeholder="der Hund" />
          </Field>
          <Field label="Translation" error="Translation is required">
            <Input defaultValue="" />
          </Field>
          <Field label="CEFR level">
            <Select
              placeholder="Choose a level…"
              options={['A1', 'A2', 'B1', 'B2', 'C1', 'C2'].map((l) => ({
                value: l,
                label: l,
              }))}
            />
          </Field>
          <Field label="Notes">
            <Textarea placeholder="Personal notes" rows={3} />
          </Field>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Badges</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Badge>Neutral</Badge>
          <Badge variant="primary">Primary</Badge>
          <Badge variant="success">Success</Badge>
          <Badge variant="warning">Warning</Badge>
          <Badge variant="danger">Danger</Badge>
          <Badge variant="info">Info</Badge>
          {(['A1', 'A2', 'B1', 'B2', 'C1', 'C2'] as const).map((level) => (
            <CefrBadge key={level} level={level} />
          ))}
          {(['NEW', 'LEARNING', 'LEARNED', 'MASTERED'] as const).map(
            (status) => (
              <StatusBadge key={status} status={status} />
            ),
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Dialogs and toasts</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={() => setModalOpen(true)}>
            Open modal
          </Button>
          <Button variant="secondary" onClick={() => setConfirmOpen(true)}>
            Confirm dialog
          </Button>
          <Button
            variant="secondary"
            onClick={() => toast({ title: 'Word saved', variant: 'success' })}
          >
            Success toast
          </Button>
          <Button
            variant="secondary"
            onClick={() =>
              toast({
                title: 'Could not save',
                description: 'Check your connection.',
                variant: 'error',
              })
            }
          >
            Error toast
          </Button>
        </CardContent>
      </Card>

      <Tabs
        label="Example tabs"
        items={[
          { id: 'table', label: 'Table', content: <ExampleTable /> },
          {
            id: 'empty',
            label: 'Empty state',
            content: (
              <EmptyState
                icon={BookOpen}
                title="No words yet"
                description="Add the vocabulary from your last lesson to start practising."
                action={<Button>Add words</Button>}
              />
            ),
          },
          {
            id: 'loading',
            label: 'Loading',
            content: <SkeletonText lines={4} />,
          },
        ]}
      />

      <Modal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        title="Edit word"
        description="Changes are saved to your vocabulary."
        footer={
          <>
            <Button variant="secondary" onClick={() => setModalOpen(false)}>
              Cancel
            </Button>
            <Button onClick={() => setModalOpen(false)}>Save</Button>
          </>
        }
      >
        <Field label="Translation">
          <Input defaultValue="dog" />
        </Field>
      </Modal>
      <ConfirmDialog
        open={confirmOpen}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() =>
          new Promise((resolve) => setTimeout(resolve, 800)).then(() =>
            setConfirmOpen(false),
          )
        }
        title="Delete this word?"
        description="Its flashcards and review history are deleted too."
        confirmLabel="Delete"
        destructive
      />
    </div>
  )
}

function ExampleTable() {
  return (
    <Table>
      <TableCaption>Example vocabulary</TableCaption>
      <THead>
        <Tr>
          <Th>Word</Th>
          <Th>Translation</Th>
          <Th>Level</Th>
          <Th>Status</Th>
        </Tr>
      </THead>
      <TBody>
        <Tr>
          <Td>der Hund</Td>
          <Td>dog</Td>
          <Td>
            <CefrBadge level="A1" />
          </Td>
          <Td>
            <StatusBadge status="LEARNED" />
          </Td>
        </Tr>
        <Tr>
          <Td>die Entscheidung</Td>
          <Td>decision</Td>
          <Td>
            <CefrBadge level="B1" />
          </Td>
          <Td>
            <StatusBadge status="NEW" />
          </Td>
        </Tr>
      </TBody>
    </Table>
  )
}
