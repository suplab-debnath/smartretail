import * as cdk from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as cwActions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as snsSubs from 'aws-cdk-lib/aws-sns-subscriptions';
import { Construct } from 'constructs';
import { ComputeStack } from './compute-stack';
import { MessagingStack } from './messaging-stack';
import { DataStack } from './data-stack';
import { ApiStack } from './api-stack';

export interface MonitoringStackProps extends cdk.StackProps {
  srEnv: string;
  compute: ComputeStack;
  messaging: MessagingStack;
  data: DataStack;
  api: ApiStack;
  alertEmail?: string;
}

const SERVICES = ['ims', 're', 'ars', 'dfs', 'sup'] as const;

export class MonitoringStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: MonitoringStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-monitoring-demo');

    const { srEnv, compute, messaging, data, api } = props;

    // ── SNS alert topic ───────────────────────────────────────────────────────
    const alertTopic = new sns.Topic(this, 'AlertTopic', {
      topicName: `smartretail-alerts-${srEnv}`,
      displayName: `SmartRetail ${srEnv} Alerts`,
    });
    if (props.alertEmail) {
      alertTopic.addSubscription(new snsSubs.EmailSubscription(props.alertEmail));
    }
    const snsAction = new cwActions.SnsAction(alertTopic);

    // ── Log metric filters ────────────────────────────────────────────────────
    // Error rate per service — fires on any Spring Boot ERROR log line
    for (const svc of SERVICES) {
      new logs.MetricFilter(this, `${svc}ErrorFilter`, {
        logGroup: logs.LogGroup.fromLogGroupName(
          this, `${svc}LgRef`, `/smartretail/${svc}/${srEnv}`),
        metricNamespace: 'SmartRetail/App',
        metricName: `${svc.toUpperCase()}_ErrorCount`,
        filterPattern: logs.FilterPattern.literal('ERROR'),
        metricValue: '1',
        defaultValue: 0,
      });
    }

    // Business pipeline event counts — proxy KPIs without service code changes
    new logs.MetricFilter(this, 'AlertRaisedFilter', {
      logGroup: logs.LogGroup.fromLogGroupName(
        this, 'ImsLgBiz', `/smartretail/ims/${srEnv}`),
      metricNamespace: 'SmartRetail/App',
      metricName: 'InventoryAlertsRaised',
      filterPattern: logs.FilterPattern.literal('InventoryAlertEvent'),
      metricValue: '1',
      defaultValue: 0,
    });
    new logs.MetricFilter(this, 'PoCreatedFilter', {
      logGroup: logs.LogGroup.fromLogGroupName(
        this, 'ReLgBiz', `/smartretail/re/${srEnv}`),
      metricNamespace: 'SmartRetail/App',
      metricName: 'PurchaseOrdersCreated',
      filterPattern: logs.FilterPattern.literal('PurchaseOrderEvent'),
      metricValue: '1',
      defaultValue: 0,
    });

    // ── Metric helpers ────────────────────────────────────────────────────────
    const p2  = cdk.Duration.minutes(2);
    const p5  = cdk.Duration.minutes(5);
    const p10 = cdk.Duration.minutes(10);

    const albM = (name: string, stat: string, period: cdk.Duration) =>
      new cloudwatch.Metric({
        namespace: 'AWS/ApplicationELB',
        metricName: name,
        dimensionsMap: { LoadBalancer: api.alb.loadBalancerFullName },
        statistic: stat,
        period,
        label: name,
      });

    const rdsM = (name: string, stat: string, period: cdk.Duration) =>
      new cloudwatch.Metric({
        namespace: 'AWS/RDS',
        metricName: name,
        dimensionsMap: { DBInstanceIdentifier: data.rdsInstance.instanceIdentifier },
        statistic: stat,
        period,
        label: name,
      });

    const ecsM = (svc: string, name: string) =>
      new cloudwatch.Metric({
        namespace: 'ECS/ContainerInsights',
        metricName: name,
        dimensionsMap: {
          ClusterName: compute.cluster.clusterName,
          ServiceName: `smartretail-${svc}-${srEnv}`,
        },
        statistic: 'Average',
        period: p5,
        label: svc.toUpperCase(),
      });

    const appM = (name: string, label: string) =>
      new cloudwatch.Metric({
        namespace: 'SmartRetail/App',
        metricName: name,
        statistic: 'Sum',
        period: p5,
        label,
      });

    // ── Alarms ────────────────────────────────────────────────────────────────
    const alarm = (
      id: string, alarmName: string, metric: cloudwatch.IMetric,
      threshold: number, evaluationPeriods: number,
      compOp = cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
    ) => {
      const a = new cloudwatch.Alarm(this, id, {
        alarmName: `SR-${alarmName}-${srEnv}`,
        metric,
        threshold,
        comparisonOperator: compOp,
        evaluationPeriods,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
      });
      a.addAlarmAction(snsAction);
      a.addOkAction(snsAction);
      return a;
    };

    const dlqAlarmImsSales = alarm(
      'DlqImsSalesAlarm', 'DLQ-ImsSales',
      messaging.imsSalesDlq.metricApproximateNumberOfMessagesVisible({ period: p5, statistic: 'Maximum' }),
      0, 1);

    const dlqAlarmReAlert = alarm(
      'DlqReAlertAlarm', 'DLQ-ReAlert',
      messaging.reAlertDlq.metricApproximateNumberOfMessagesVisible({ period: p5, statistic: 'Maximum' }),
      0, 1);

    const dlqAlarmArsUpdates = alarm(
      'DlqArsUpdatesAlarm', 'DLQ-ArsUpdates',
      messaging.arsUpdatesDlq.metricApproximateNumberOfMessagesVisible({ period: p5, statistic: 'Maximum' }),
      0, 1);

    const albUnhealthyAlarm = alarm(
      'AlbUnhealthyAlarm', 'ALB-UnhealthyHosts',
      albM('UnHealthyHostCount', 'Maximum', p2),
      0, 1);

    const alb5xxAlarm = alarm(
      'Alb5xxAlarm', 'ALB-5xxErrors',
      albM('HTTPCode_ELB_5XX_Count', 'Sum', p5),
      10, 1);

    const rdsCpuAlarm = alarm(
      'RdsCpuAlarm', 'RDS-CPUHigh',
      rdsM('CPUUtilization', 'Average', p10),
      80, 2);

    const allAlarms = [
      dlqAlarmImsSales, dlqAlarmReAlert, dlqAlarmArsUpdates,
      albUnhealthyAlarm, alb5xxAlarm, rdsCpuAlarm,
    ];

    // ── Dashboard ─────────────────────────────────────────────────────────────
    const dashboard = new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: `SmartRetail-${srEnv}-Ops`,
      defaultInterval: cdk.Duration.hours(3),
    });

    // Title bar
    dashboard.addWidgets(
      new cloudwatch.TextWidget({
        markdown: [
          `# SmartRetail ${srEnv.toUpperCase()} — SC Planner Ops Dashboard`,
          `**Services:** IMS · RE · ARS · DFS · SUP  |  **Env:** ${srEnv}  |  **Stack:** cdk-min`,
        ].join('\n'),
        width: 24, height: 2,
      }),
    );

    // Row 1 — ALB / API layer
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'API Requests / min',
        width: 6, height: 6,
        left: [albM('RequestCount', 'Sum', p5)],
      }),
      new cloudwatch.GraphWidget({
        title: 'API 5xx Errors',
        width: 6, height: 6,
        left: [albM('HTTPCode_ELB_5XX_Count', 'Sum', p5)],
        leftAnnotations: [{ value: 10, color: '#ff0000', label: 'Alert threshold' }],
      }),
      new cloudwatch.GraphWidget({
        title: 'Target Response Time (avg s)',
        width: 6, height: 6,
        left: [albM('TargetResponseTime', 'Average', p5)],
      }),
      new cloudwatch.SingleValueWidget({
        title: 'Unhealthy Hosts',
        width: 6, height: 6,
        metrics: [albM('UnHealthyHostCount', 'Maximum', p2)],
        sparkline: true,
      }),
    );

    // Row 2 — Business pipeline KPIs (from log metric filters)
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'Business Pipeline Events / 5 min',
        width: 12, height: 6,
        left: [
          appM('InventoryAlertsRaised', 'Inventory Alerts Raised'),
          appM('PurchaseOrdersCreated', 'POs Created'),
        ],
      }),
      new cloudwatch.GraphWidget({
        title: 'Application Errors by Service / 5 min',
        width: 12, height: 6,
        left: SERVICES.map(svc => appM(`${svc.toUpperCase()}_ErrorCount`, svc.toUpperCase())),
        stacked: true,
      }),
    );

    // Row 3 — ECS Container Insights
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'ECS CPU Utilised (vCPU units)',
        width: 12, height: 6,
        left: SERVICES.map(svc => ecsM(svc, 'CpuUtilized')),
      }),
      new cloudwatch.GraphWidget({
        title: 'ECS Memory Utilised (MiB)',
        width: 12, height: 6,
        left: SERVICES.map(svc => ecsM(svc, 'MemoryUtilized')),
      }),
    );

    // Row 4 — SQS queue depths
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'SQS Queue Depths',
        width: 12, height: 6,
        left: [
          messaging.imsSalesQueue.metricApproximateNumberOfMessagesVisible({ period: p5, label: 'IMS Sales' }),
          messaging.reAlertQueue.metricApproximateNumberOfMessagesVisible({ period: p5, label: 'RE Alert' }),
          messaging.arsUpdatesQueue.metricApproximateNumberOfMessagesVisible({ period: p5, label: 'ARS Updates' }),
        ],
      }),
      new cloudwatch.GraphWidget({
        title: 'DLQ Depths (target: 0)',
        width: 12, height: 6,
        left: [
          messaging.imsSalesDlq.metricApproximateNumberOfMessagesVisible({ period: p5, label: 'IMS-Sales DLQ' }),
          messaging.reAlertDlq.metricApproximateNumberOfMessagesVisible({ period: p5, label: 'RE-Alert DLQ' }),
          messaging.arsUpdatesDlq.metricApproximateNumberOfMessagesVisible({ period: p5, label: 'ARS-Updates DLQ' }),
        ],
        leftAnnotations: [{ value: 0, color: '#ff6600', label: 'Any message triggers alarm' }],
      }),
    );

    // Row 5 — RDS
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'RDS CPU Utilization %',
        width: 8, height: 6,
        left: [rdsM('CPUUtilization', 'Average', p5)],
        leftAnnotations: [{ value: 80, color: '#ff0000', label: 'Alert threshold' }],
      }),
      new cloudwatch.GraphWidget({
        title: 'RDS DB Connections',
        width: 8, height: 6,
        left: [rdsM('DatabaseConnections', 'Average', p5)],
      }),
      new cloudwatch.SingleValueWidget({
        title: 'RDS Free Storage (GB)',
        width: 8, height: 6,
        metrics: [
          new cloudwatch.MathExpression({
            expression: 'FLOOR(m1 / 1073741824)',
            usingMetrics: { m1: rdsM('FreeStorageSpace', 'Minimum', p5) },
            label: 'Free Storage (GB)',
          }),
        ],
        sparkline: false,
      }),
    );

    // Row 6 — Log Insights: live log tails for errors and business events
    dashboard.addWidgets(
      new cloudwatch.LogQueryWidget({
        title: 'Recent Application Errors (last 1h)',
        width: 12, height: 8,
        logGroupNames: SERVICES.map(svc => `/smartretail/${svc}/${srEnv}`),
        view: cloudwatch.LogQueryVisualizationType.TABLE,
        queryLines: [
          'fields @timestamp, @logStream, @message',
          'filter @message =~ /ERROR/',
          'sort @timestamp desc',
          '| limit 25',
        ],
      }),
      new cloudwatch.LogQueryWidget({
        title: 'Business Pipeline Events (last 1h)',
        width: 12, height: 8,
        logGroupNames: SERVICES.map(svc => `/smartretail/${svc}/${srEnv}`),
        view: cloudwatch.LogQueryVisualizationType.TABLE,
        queryLines: [
          'fields @timestamp, @logStream, @message',
          'filter @message =~ /InventoryAlertEvent|PurchaseOrderEvent/',
          'sort @timestamp desc',
          '| limit 25',
        ],
      }),
    );

    // Row 7 — Alarm status overview
    dashboard.addWidgets(
      new cloudwatch.AlarmStatusWidget({
        title: 'Alarm Status',
        width: 24, height: 4,
        alarms: allAlarms,
        sortBy: cloudwatch.AlarmStatusWidgetSortBy.STATE_UPDATED_TIMESTAMP,
      }),
    );
  }
}
