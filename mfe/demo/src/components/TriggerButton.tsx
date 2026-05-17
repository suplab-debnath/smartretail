import type { RunState } from '../hooks/useFlowRunner'

interface Props {
  label:         string
  description:   string
  state:         RunState
  onTrigger:     () => void
  onMouseEnter?: () => void
}

export default function TriggerButton({ label, description, state, onTrigger, onMouseEnter }: Props) {
  const isRunning = state === 'running'
  const isDone    = state === 'done'
  const isFailed  = state === 'failed'

  return (
    <div className="my-4">
      <button
        onClick={onTrigger}
        onMouseEnter={onMouseEnter}
        disabled={isRunning}
        className={`w-full py-3 px-5 rounded-lg font-semibold text-sm transition-all flex items-center justify-center gap-3
          ${isRunning ? 'bg-blue-900 text-blue-300 cursor-wait'                        : ''}
          ${isDone    ? 'bg-emerald-900 text-emerald-300 border border-emerald-700'    : ''}
          ${isFailed  ? 'bg-red-900 text-red-300 border border-red-700'                : ''}
          ${state === 'idle' ? 'bg-blue-600 hover:bg-blue-500 text-white cursor-pointer' : ''}`}
      >
        {isRunning && (
          <svg className="animate-spin w-4 h-4" viewBox="0 0 24 24" fill="none">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"/>
            <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"/>
          </svg>
        )}
        {isDone   && <span>✓</span>}
        {isFailed && <span>✗</span>}
        {state === 'idle' && <span>▶</span>}
        <span>
          {isRunning ? 'Running…' : isDone ? 'Done — check the log' : isFailed ? 'Failed — retry?' : label}
        </span>
      </button>
      <p className="text-xs text-slate-600 mt-1 text-center">{description}</p>
    </div>
  )
}
