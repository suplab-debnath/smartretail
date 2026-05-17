export type ServiceId =
  | 'kinesis' | 'lambda' | 'sis' | 'ims' | 're' | 'ars' | 'dfs' | 'sup'
  | 'eventbridge' | 'sqs' | 'rds';

export type NodeState   = 'idle' | 'active' | 'success' | 'error';
export type FlowStatus  = 'not_started' | 'in_progress' | 'complete' | 'failed';
export type StepStatus  = 'pending' | 'running' | 'done' | 'failed';
export type LogLevel    = 'pass' | 'fail' | 'warn' | 'info' | 'event';

export interface EventLogEntry {
  id:      string;
  ts:      string;
  flowId:  string;
  stepId:  string;
  service: string;
  level:   LogLevel;
  message: string;
  raw:     string;
}

export interface ChecklistItem {
  id:           string;
  text:         string;
  matchPattern: string; // substring match against log messages
}

export interface DbQuery {
  key:      string;
  label:    string;
  endpoint: string; // e.g. /api/dbstate/stock-alerts
  params?:  Record<string, string>;
  /** field name expected to change after trigger fires */
  changeKey?: string;
}

export interface TriggerDef {
  label:       string;
  endpoint:    string; // POST /api/trigger/flow1/pos-event
  body?:       Record<string, unknown>;
  description: string;
}

export type MfeKey = 'store-manager' | 'sc-planner' | 'executive';

export interface MfeReveal {
  mfe:       MfeKey;
  localPort: number;
  path:      string;
  label:     string;
}

export interface StepDef {
  id:           string;
  title:        string;
  narrative:    string;
  trigger?:     TriggerDef;
  checklist?:   ChecklistItem[];
  dbQueries?:   DbQuery[];
  mfeReveal?:   MfeReveal;
  activeNodes?: ServiceId[];
  flowEdges?:   Array<[ServiceId, ServiceId]>;
}

export interface FlowDef {
  id:            string;
  chapterNumber: number;
  title:         string;
  subtitle:      string;
  persona?:      string;
  colorClass:    string; // Tailwind bg class for the hero
  steps:         StepDef[];
}

export interface ServiceNodeStatus {
  id:             ServiceId;
  state:          NodeState;
  awsResourceId?: string;
}

export interface DbSnapshot {
  rows:      Record<string, unknown>[];
  timestamp: string;
}

export interface HealthStatus {
  sis: 'ok' | 'down';
  ims: 'ok' | 'down';
  re:  'ok' | 'down';
  ars: 'ok' | 'down';
  dfs: 'ok' | 'down';
  sup: 'ok' | 'down';
}
