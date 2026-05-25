// Adapted from tools/demo/ui/src/components/ArchitectureDiagram.tsx
// Shows only the replenishment pipeline path relevant to the SC Planner demo.

type NodeState = 'idle' | 'active' | 'success' | 'error'
type DemoPhase = 'idle' | 'injecting' | 'polling' | 'found' | 'approving' | 'done' | 'timeout'

const W = 650
const H = 262
const NW = 90
const NH = 36
const R = 6

type NodeId = 'sis' | 'eventbridge' | 'ims' | 'rds' | 'sqs' | 're'

type NodeDef = { id: NodeId; label: string; x: number; y: number }

const NODES: NodeDef[] = [
  { id: 'sis',         label: 'SIS',         x: 20,  y: 20  },
  { id: 'eventbridge', label: 'EventBridge', x: 160, y: 20  },
  { id: 'ims',         label: 'IMS',         x: 365, y: 20  },
  { id: 'rds',         label: 'RDS',         x: 540, y: 20  },
  { id: 'sqs',         label: 'SQS',         x: 160, y: 130 },
  { id: 're',          label: 'RE',          x: 365, y: 130 },
]

// SC Planner approval box (human-in-the-loop, rendered separately)
const SC_X = 220
const SC_Y = 208
const SC_W = 210
const SC_H = 36

const NODE_STYLE: Record<NodeState, { fill: string; stroke: string; text: string }> = {
  idle:    { fill: '#1e293b', stroke: '#334155', text: '#94a3b8' },
  active:  { fill: '#1d3a52', stroke: '#3b82f6', text: '#93c5fd' },
  success: { fill: '#14322a', stroke: '#22c55e', text: '#86efac' },
  error:   { fill: '#3b1414', stroke: '#ef4444', text: '#fca5a5' },
}

// Teal tones for the SC Planner box in idle state to mark it as "human in the loop"
const SC_IDLE = { fill: '#0c1a1a', stroke: '#0e7490', text: '#22d3ee' }

type EdgeId = NodeId | 'sc-planner'
type EdgeDef = [EdgeId, EdgeId]

const STATIC_EDGES: Array<[NodeId, NodeId]> = [
  ['sis',         'eventbridge'],
  ['eventbridge', 'ims'],
  ['ims',         'rds'],
  ['eventbridge', 'sqs'],
  ['sqs',         're'],
  ['re',          'rds'],
]

function cx(node: NodeDef) { return node.x + NW / 2 }
function cy(node: NodeDef) { return node.y + NH / 2 }
function scCx() { return SC_X + SC_W / 2 }
function scCy() { return SC_Y + SC_H / 2 }

function buildPath(x1: number, y1: number, x2: number, y2: number): string {
  const mx = (x1 + x2) / 2
  return `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`
}

interface Props {
  completedSteps: number
  phase: DemoPhase
}

export function ReplenishmentFlowDiagram({ completedSteps, phase }: Props) {
  const nodeMap = Object.fromEntries(NODES.map(n => [n.id, n])) as Record<NodeId, NodeDef>

  // ── Node states ─────────────────────────────────────────────────────────────
  const nodeStates: Partial<Record<NodeId, NodeState>> = {}

  if (phase === 'injecting') {
    nodeStates['sis'] = 'active'
  } else {
    if (completedSteps >= 1) nodeStates['sis'] = 'success'
    if (completedSteps >= 2) { nodeStates['eventbridge'] = 'success'; nodeStates['ims'] = 'success' }
    if (completedSteps >= 3) nodeStates['sqs'] = 'success'
    if (completedSteps >= 4) { nodeStates['re'] = 'success'; nodeStates['rds'] = 'success' }

    if (phase === 'polling') {
      if (completedSteps === 1) { nodeStates['eventbridge'] = 'active'; nodeStates['ims'] = 'active' }
      else if (completedSteps === 2) nodeStates['sqs'] = 'active'
      else if (completedSteps === 3) { nodeStates['re'] = 'active'; nodeStates['rds'] = 'active' }
    }
  }

  const scState: NodeState =
    phase === 'done' ? 'success' :
    (phase === 'found' || phase === 'approving') ? 'active' : 'idle'

  // ── Active edges ─────────────────────────────────────────────────────────────
  const activeEdges: EdgeDef[] = []
  if (phase === 'injecting') {
    activeEdges.push(['sis', 'eventbridge'])
  } else if (phase === 'polling') {
    if (completedSteps === 1) {
      activeEdges.push(['sis', 'eventbridge'])
      activeEdges.push(['eventbridge', 'ims'])
    } else if (completedSteps === 2) {
      activeEdges.push(['ims', 'rds'])
      activeEdges.push(['eventbridge', 'sqs'])
    } else if (completedSteps === 3) {
      activeEdges.push(['sqs', 're'])
      activeEdges.push(['re', 'rds'])
    }
  } else if (phase === 'found' || phase === 'approving') {
    activeEdges.push(['rds', 'sc-planner'])
  }

  function isActive(a: EdgeId, b: EdgeId) {
    return activeEdges.some(([x, y]) => (x === a && y === b) || (x === b && y === a))
  }

  const scEdgeActive = isActive('rds', 'sc-planner')
  const scStyle = scState === 'idle' ? SC_IDLE : NODE_STYLE[scState]

  // RDS → SC Planner edge path (center-to-center)
  const rds = nodeMap['rds']
  const rdsToScPath = buildPath(cx(rds), cy(rds), scCx(), scCy())

  return (
    <div className="w-full overflow-x-auto rounded-lg bg-slate-950 p-3">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="w-full"
        style={{ minWidth: 520, maxHeight: 240 }}
        aria-label="Replenishment pipeline flow diagram"
      >
        {/* ── Static service edges ── */}
        {STATIC_EDGES.map(([a, b]) => {
          const from = nodeMap[a]
          const to   = nodeMap[b]
          if (!from || !to) return null
          const active = isActive(a, b)
          const d = buildPath(cx(from), cy(from), cx(to), cy(to))
          return (
            <g key={`${a}-${b}`}>
              <path
                d={d}
                fill="none"
                stroke={active ? '#3b82f6' : '#1e3a5f'}
                strokeWidth={active ? 2 : 1.5}
                strokeDasharray={active ? undefined : '5 4'}
              />
              {active && (
                <circle r="4" fill="#60a5fa">
                  <animateMotion dur="1.2s" repeatCount="indefinite" path={d} />
                </circle>
              )}
            </g>
          )
        })}

        {/* ── RDS → SC Planner edge ── */}
        <g>
          <path
            d={rdsToScPath}
            fill="none"
            stroke={scEdgeActive ? '#3b82f6' : '#1e3a5f'}
            strokeWidth={scEdgeActive ? 2 : 1.5}
            strokeDasharray={scEdgeActive ? undefined : '5 4'}
          />
          {scEdgeActive && (
            <circle r="4" fill="#60a5fa">
              <animateMotion dur="1.2s" repeatCount="indefinite" path={rdsToScPath} />
            </circle>
          )}
        </g>

        {/* ── Service nodes ── */}
        {NODES.map(node => {
          const state  = nodeStates[node.id] ?? 'idle'
          const style  = NODE_STYLE[state]
          const active = state === 'active'
          return (
            <g key={node.id}>
              {active && (
                <rect
                  x={node.x - 4} y={node.y - 4}
                  width={NW + 8} height={NH + 8}
                  rx={R + 2} fill="none"
                  stroke={style.stroke} strokeWidth="1.5" opacity="0.4"
                />
              )}
              <rect
                x={node.x} y={node.y}
                width={NW} height={NH}
                rx={R} fill={style.fill} stroke={style.stroke} strokeWidth="1.5"
              />
              <text
                x={node.x + NW / 2} y={node.y + NH / 2 + 4}
                textAnchor="middle" fontSize="11"
                fill={style.text} fontWeight="600" fontFamily="monospace"
              >
                {node.label}
              </text>
            </g>
          )
        })}

        {/* ── SC Planner approval box ── */}
        <g>
          {scState === 'active' && (
            <rect
              x={SC_X - 4} y={SC_Y - 4}
              width={SC_W + 8} height={SC_H + 8}
              rx={R + 2} fill="none"
              stroke={scStyle.stroke} strokeWidth="1.5" opacity="0.4"
            />
          )}
          <rect
            x={SC_X} y={SC_Y}
            width={SC_W} height={SC_H}
            rx={R} fill={scStyle.fill} stroke={scStyle.stroke} strokeWidth="1.5"
          />
          <text
            x={SC_X + SC_W / 2} y={SC_Y + SC_H / 2 + 4}
            textAnchor="middle" fontSize="11"
            fill={scStyle.text} fontWeight="600" fontFamily="monospace"
          >
            SC Planner — Approve PO
          </text>
        </g>

        {/* ── Row labels ── */}
        <text x="65"  y="76" textAnchor="middle" fontSize="9" fill="#475569">① POS sale</text>
        <text x="205" y="76" textAnchor="middle" fontSize="9" fill="#475569">② events</text>
        <text x="410" y="76" textAnchor="middle" fontSize="9" fill="#475569">③ stock update</text>
        <text x="585" y="76" textAnchor="middle" fontSize="9" fill="#475569">⑤ PO stored</text>
        <text x="205" y="186" textAnchor="middle" fontSize="9" fill="#475569">③ alert queue</text>
        <text x="410" y="186" textAnchor="middle" fontSize="9" fill="#475569">④ raise PO</text>
      </svg>
    </div>
  )
}
