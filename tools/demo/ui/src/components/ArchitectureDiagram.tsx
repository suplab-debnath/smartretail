import type { ServiceId, NodeState } from '../types'

// ── Layout constants ────────────────────────────────────────────────────────
const W = 860
const H = 420
const NW = 90  // node width
const NH = 36  // node height
const R = 6    // border radius

type NodeDef = { id: ServiceId; label: string; x: number; y: number; layer: number }

const NODES: NodeDef[] = [
  // Layer 0 — ingest
  { id: 'firehose',    label: 'Firehose',     x: 30,  y: 40,  layer: 0 },
  { id: 'sis',         label: 'SIS',          x: 160, y: 40,  layer: 0 },
  { id: 'eventbridge', label: 'EventBridge',  x: 310, y: 40,  layer: 0 },
  // Layer 1 — process
  { id: 'ims',         label: 'IMS',          x: 460, y: 40,  layer: 1 },
  { id: 'sqs',         label: 'SQS (RE)',     x: 310, y: 140, layer: 1 },
  { id: 're',          label: 'RE',           x: 460, y: 140, layer: 1 },
  // Layer 2 — aggregate + DFS/SUP
  { id: 'dfs',         label: 'DFS',          x: 610, y: 40,  layer: 2 },
  { id: 'sup',         label: 'SUP',          x: 610, y: 140, layer: 2 },
  { id: 'rds',         label: 'RDS',          x: 610, y: 240, layer: 2 },
  { id: 'ars',         label: 'ARS',          x: 310, y: 260, layer: 2 },
]

// Static edges as [fromId, toId]
type EdgeDef = [ServiceId, ServiceId]
const STATIC_EDGES: EdgeDef[] = [
  ['firehose', 'sis'],
  ['sis',     'eventbridge'],
  ['eventbridge', 'ims'],
  ['eventbridge', 'sqs'],
  ['sqs',     're'],
  ['ims',     'rds'],
  ['re',      'rds'],
  ['dfs',     'rds'],
  ['sup',     'rds'],
  ['rds',     'ars'],
]

const NODE_STYLE: Record<NodeState, { fill: string; stroke: string; text: string }> = {
  idle:    { fill: '#1e293b', stroke: '#334155', text: '#94a3b8' },
  active:  { fill: '#1d3a52', stroke: '#3b82f6', text: '#93c5fd' },
  success: { fill: '#14322a', stroke: '#22c55e', text: '#86efac' },
  error:   { fill: '#3b1414', stroke: '#ef4444', text: '#fca5a5' },
}

function cx(node: NodeDef) { return node.x + NW / 2 }
function cy(node: NodeDef) { return node.y + NH / 2 }

function buildPath(from: NodeDef, to: NodeDef): string {
  const x1 = cx(from), y1 = cy(from)
  const x2 = cx(to),   y2 = cy(to)
  const mx = (x1 + x2) / 2
  // Simple bezier curve
  return `M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2} ${y2}`
}

interface Props {
  nodeStates:    Partial<Record<ServiceId, NodeState>>
  activeEdges?:  Array<[ServiceId, ServiceId]>
}

export default function ArchitectureDiagram({ nodeStates, activeEdges = [] }: Props) {
  const nodeMap = Object.fromEntries(NODES.map(n => [n.id, n])) as Record<ServiceId, NodeDef>

  const isEdgeActive = (a: ServiceId, b: ServiceId) =>
    activeEdges.some(([x, y]) => (x === a && y === b) || (x === b && y === a))

  return (
    <div className="w-full overflow-x-auto">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="w-full"
        style={{ minWidth: 600, maxHeight: 300 }}
        aria-label="SmartRetail service architecture"
      >
        {/* ── MFE strip at the bottom ── */}
        <g>
          {/* ARS → MFEs connector lines */}
          {(['store-manager', 'sc-planner', 'executive'] as const).map((mfe, i) => {
            const mfeX = 120 + i * 220
            const mfeY = 340
            const arsNode = nodeMap['ars']
            return (
              <line
                key={mfe}
                x1={cx(arsNode)} y1={arsNode.y + NH}
                x2={mfeX + 90}  y2={mfeY}
                stroke="#1e3a5f" strokeWidth="1.5" strokeDasharray="4 3"
              />
            )
          })}
          {/* MFE boxes */}
          {[
            { label: 'Store Manager', color: '#7c3aed' },
            { label: 'SC Planner',    color: '#0e7490' },
            { label: 'Executive',     color: '#be123c' },
          ].map(({ label, color }, i) => (
            <g key={label}>
              <rect
                x={120 + i * 220} y={340} width={180} height={36}
                rx={R} fill="#0f172a" stroke={color} strokeWidth="1.5"
              />
              <text
                x={120 + i * 220 + 90} y={363}
                textAnchor="middle" fontSize="11" fill={color} fontFamily="monospace"
              >
                {label} MFE
              </text>
            </g>
          ))}
        </g>

        {/* ── Static edges ── */}
        {STATIC_EDGES.map(([a, b]) => {
          const from = nodeMap[a]
          const to   = nodeMap[b]
          if (!from || !to) return null
          const active = isEdgeActive(a, b)
          return (
            <g key={`${a}-${b}`} className={active ? 'edge-active' : ''}>
              <path
                d={buildPath(from, to)}
                fill="none"
                stroke={active ? '#3b82f6' : '#1e3a5f'}
                strokeWidth={active ? 2 : 1.5}
                strokeDasharray={active ? undefined : '5 4'}
              />
              {active && (
                <circle r="4" fill="#60a5fa">
                  <animateMotion
                    dur="1.2s"
                    repeatCount="indefinite"
                    path={buildPath(from, to)}
                  />
                </circle>
              )}
            </g>
          )
        })}

        {/* ── Service nodes ── */}
        {NODES.map(node => {
          const state  = nodeStates[node.id] ?? 'idle'
          const style  = NODE_STYLE[state]
          const isActive = state === 'active'

          return (
            <g key={node.id}>
              {/* pulse ring for active state */}
              {isActive && (
                <rect
                  x={node.x - 4} y={node.y - 4}
                  width={NW + 8} height={NH + 8}
                  rx={R + 2} fill="none"
                  stroke={style.stroke} strokeWidth="1.5" opacity="0.4"
                  className="node-ring"
                />
              )}
              <rect
                x={node.x} y={node.y} width={NW} height={NH}
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
      </svg>
    </div>
  )
}
