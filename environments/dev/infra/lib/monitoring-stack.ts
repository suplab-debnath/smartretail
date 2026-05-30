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

const SERVICES = ['sis', 'ims', 're', 'ars', 'dfs', 'sup', 'pps'] as const;

export class MonitoringStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: MonitoringStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-monitoring-dev');

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

    // Business pipeline event counts
    new logs.MetricFilter(this, 'PosEventsFilter', {
      logGroup: logs.LogGroup.fromLogGroupName(this, 'SisLgBiz', `/smartretail/sis/${srEnv}`),
      metricNamespace: 'SmartRetail/App',
      metricName: 'POSEventsIngested',
      filterPattern: logs.FilterPattern.literal('SalesTransactionEvent'),
      metricValue: '1',
      defaultValue: 0,
    });
    new logs.MetricFilter(this, 'AlertRaisedFilter', {
      logGroup: logs.LogGroup.fromLogGroupName(this, 'ImsLgBiz', `/smartretail/ims/${srEnv}`),
      metricNamespace: 'SmartRetail/App',
      metricName: 'InventoryAlertsRaised',
      filterPattern: logs.FilterPattern.literal('InventoryAlertEvent'),
      metricValue: '1',
      defaultValue: 0,
    });
    new logs.MetricFilter(this, 'PoCreatedFilter', {
      logGroup: logs.LogGroup.fromLogGroupName(this, 'ReLgBiz', `/smartretail/re/${srEnv}`),
      metricNamespace: 'SmartRetail/App',
      metricName: 'PurchaseOrdersCreated',
      filterPattern: logs.FilterPattern.literal('PurchaseOrderEvent'),
      metricValue: '1',
      defaultValue: 0,
    });

    // ── Metric helpers ────────────────────────────────────────────────────────
    const p5  = cdk.Duration.minutes(5);
    const p10 = cdk.Duration.minutes(10);

    // API Gateway HTTP API metrics (replace ALB)
    const apiM = (name: string, stat: string, period: cdk.Duration) =>
      new cloudwatch.Metric({
        namespace: 'AWS/ApiGateway',
        metricName: name,
        dimensionsMap: { ApiId: api.apiEndpoint.split('.')[0].replace('https://', '') },
        statistic: stat,
        period,
        label: name,
      });

    // Firehose metrics
    const firehoseM = (name: string, stat: string, period: cdk.Duration) =>
      new cloudwatch.Metric({
        namespace: 'AWS/Firehose',
        metricName: name,
        dimensionsMap: { DeliveryStreamName: api.firehoseStreamName },
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

    const api5xxAlarm = alarm(
      'Api5xxAlarm', 'API-5xxErrors',
      apiM('5XXError', 'Sum', p5),
      10, 1);

    const rdsCpuAlarm = alarm(
      'RdsCpuAlarm', 'RDS-CPUHigh',
      rdsM('CPUUtilization', 'Average', p10),
      80, 2);

    const firehoseFailedAlarm = alarm(
      'FirehoseDeliveryFailedAlarm', 'Firehose-DeliveryFailed',
      firehoseM('DeliveryToHttpEndpoint.DataFreshness', 'Maximum', p5),
      600, 2);

    const allAlarms = [
      dlqAlarmImsSales, dlqAlarmReAlert, dlqAlarmArsUpdates,
      api5xxAlarm, rdsCpuAlarm, firehoseFailedAlarm,
    ];

    // ── Dashboard ─────────────────────────────────────────────────────────────
    const dashboard = new cloudwatch.Dashboard(this, 'Dashboard', {
      dashboardName: `SmartRetail-${srEnv}-Ops`,
      defaultInterval: cdk.Duration.hours(3),
    });

    dashboard.addWidgets(
      new cloudwatch.TextWidget({
        markdown: [
          `# SmartRetail ${srEnv.toUpperCase()} — Ops Dashboard`,
          `**Services:** SIS · IMS · RE · ARS · DFS · SUP · PPS  |  **Env:** ${srEnv}  |  **Stack:** cdk-dev`,
        ].join('\n'),
        width: 24, height: 2,
      }),
    );

    // Row 1 — API Gateway layer
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'API Requests / 5 min',
        width: 6, height: 6,
        left: [apiM('Count', 'Sum', p5)],
      }),
      new cloudwatch.GraphWidget({
        title: 'API 5xx Errors',
        width: 6, height: 6,
        left: [apiM('5XXError', 'Sum', p5)],
        leftAnnotations: [{ value: 10, color: '#ff0000', label: 'Alert threshold' }],
      }),
      new cloudwatch.GraphWidget({
        title: 'API Latency p99 (ms)',
        width: 6, height: 6,
        left: [apiM('Latency', 'p99', p5)],
      }),
      new cloudwatch.GraphWidget({
        title: 'Firehose Data Freshness (s)',
        width: 6, height: 6,
        left: [firehoseM('DeliveryToHttpEndpoint.DataFreshness', 'Maximum', p5)],
        leftAnnotations: [{ value: 600, color: '#ff0000', label: 'Alert threshold' }],
      }),
    );

    // Row 2 — Business pipeline KPIs
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'Business Pipeline Events / 5 min',
        width: 12, height: 6,
        left: [
          appM('POSEventsIngested', 'POS Events Ingested'),
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

    // Row 3 — ECS cluster
    dashboard.addWidgets(
      ...(['sis', 'ims', 're', 'ars'] as const).map(svc =>
        new cloudwatch.GraphWidget({
          title: `${svc.toUpperCase()} CPU %`,
          width: 6, height: 6,
          left: [ecsM(svc, 'CpuUtilized')],
        }),
      ),
    );

    // Row 4 — RDS + SQS DLQs
    dashboard.addWidgets(
      new cloudwatch.GraphWidget({
        title: 'RDS CPU %',
        width: 6, height: 6,
        left: [rdsM('CPUUtilization', 'Average', p10)],
        leftAnnotations: [{ value: 80, color: '#ff0000', label: 'Alert threshold' }],
      }),
      new cloudwatch.GraphWidget({
        title: 'RDS Connections',
        width: 6, height: 6,
        left: [rdsM('DatabaseConnections', 'Average', p10)],
      }),
      new cloudwatch.GraphWidget({
        title: 'SQS DLQ depths',
        width: 12, height: 6,
        left: [
          messaging.imsSalesDlq.metricApproximateNumberOfMessagesVisible({ period: p5, statistic: 'Maximum', label: 'IMS Sales DLQ' }),
          messaging.reAlertDlq.metricApproximateNumberOfMessagesVisible({ period: p5, statistic: 'Maximum', label: 'RE Alert DLQ' }),
          messaging.arsUpdatesDlq.metricApproximateNumberOfMessagesVisible({ period: p5, statistic: 'Maximum', label: 'ARS Updates DLQ' }),
        ],
        leftAnnotations: [{ value: 1, color: '#ff0000', label: 'Alert threshold' }],
      }),
    );

    // Row 5 — Alarm summary
    dashboard.addWidgets(
      new cloudwatch.AlarmStatusWidget({
        title: 'Active Alarms',
        alarms: allAlarms,
        width: 24, height: 4,
      }),
    );

    // Suppress unused variable warning
    void allAlarms;
  }
}
