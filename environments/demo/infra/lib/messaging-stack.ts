import * as cdk from 'aws-cdk-lib';
import * as events from 'aws-cdk-lib/aws-events';
import * as eventsTargets from 'aws-cdk-lib/aws-events-targets';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface MessagingStackProps extends cdk.StackProps {
  srEnv: string;
}

/**
 * Demo messaging stack — SQS inter-service routing for the live pipeline.
 *
 * Active queues (consumers implemented):
 *   ims-sales   — SalesTransactionEvent → IMS (stock decrement + alert)
 *   re-alert    — InventoryAlertEvent   → RE  (PO creation, FIFO per DC+SKU)
 *
 * Provisioned / no consumer yet:
 *   ims-po       — ReplenishmentOrderApproved → IMS (in_transit update) — deferred
 *   ims-forecast — ForecastReadyDomainEvent   → IMS (dynamic reorder_point) — deferred
 *
 * Removed:
 *   ars-updates  — ARS does live RDS queries; no SQS consumer exists or is planned.
 */
export class MessagingStack extends cdk.Stack {
  public readonly eventBus: events.EventBus;
  public readonly imsSalesQueue: sqs.Queue;
  public readonly imsSalesDlq: sqs.Queue;
  public readonly reAlertQueue: sqs.Queue;
  public readonly reAlertDlq: sqs.Queue;
  public readonly imsPoQueue: sqs.Queue;
  public readonly imsPoDlq: sqs.Queue;
  public readonly imsForecQueue: sqs.Queue;
  public readonly imsForecDlq: sqs.Queue;
  public readonly salesToImsRule: events.Rule;
  public readonly alertToReRule: events.Rule;

  constructor(scope: Construct, id: string, props: MessagingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-messaging-demo');

    const { srEnv } = props;

    this.eventBus = new events.EventBus(this, 'SmartRetailBus', {
      eventBusName: `smartretail-events-${srEnv}`,
    });

    this.imsSalesDlq = new sqs.Queue(this, 'ImsSalesDlq', {
      queueName: `smartretail-ims-sales-${srEnv}-dlq`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      retentionPeriod: cdk.Duration.days(14),
    });
    this.imsSalesQueue = new sqs.Queue(this, 'ImsSalesQueue', {
      queueName: `smartretail-ims-sales-${srEnv}`,
      deadLetterQueue: { queue: this.imsSalesDlq, maxReceiveCount: 3 },
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    this.reAlertDlq = new sqs.Queue(this, 'ReAlertDlq', {
      queueName: `smartretail-re-alert-${srEnv}-dlq.fifo`,
      fifo: true,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      retentionPeriod: cdk.Duration.days(14),
    });
    this.reAlertQueue = new sqs.Queue(this, 'ReAlertQueue', {
      queueName: `smartretail-re-alert-${srEnv}.fifo`,
      fifo: true,
      contentBasedDeduplication: true,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: this.reAlertDlq, maxReceiveCount: 3 },
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    // ims-po: provisioned for future 2 — no EventBridge rule yet (no consumer)
    // Wire: RE ReplenishmentOrderApproved → increment in_transit, resolve open alert
    this.imsPoDlq = new sqs.Queue(this, 'ImsPoQueueDlq', {
      queueName: `smartretail-ims-po-${srEnv}-dlq`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      retentionPeriod: cdk.Duration.days(14),
    });
    this.imsPoQueue = new sqs.Queue(this, 'ImsPoQueue', {
      queueName: `smartretail-ims-po-${srEnv}`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: this.imsPoDlq, maxReceiveCount: 3 },
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    // ims-forecast: provisioned for future dynamic reorder_point — no consumer yet
    // Wire: batch-post-processor Lambda ForecastReadyDomainEvent → IMS adjusts threshold per SKU/DC
    this.imsForecDlq = new sqs.Queue(this, 'ImsForecQueueDlq', {
      queueName: `smartretail-ims-forecast-${srEnv}-dlq`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      retentionPeriod: cdk.Duration.days(14),
    });
    this.imsForecQueue = new sqs.Queue(this, 'ImsForecQueue', {
      queueName: `smartretail-ims-forecast-${srEnv}`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: this.imsForecDlq, maxReceiveCount: 3 },
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    // SIS raises SalesTransactionEvent → IMS picks up for stock decrement
    this.salesToImsRule = new events.Rule(this, 'SalesToIms', {
      eventBus: this.eventBus,
      ruleName: `smartretail-sales-to-ims-${srEnv}`,
      eventPattern: { source: ['smartretail.sis'], detailType: ['SalesTransactionEvent'] },
      targets: [new eventsTargets.SqsQueue(this.imsSalesQueue)],
    });

    // IMS raises InventoryAlertEvent → RE picks up for PO creation
    this.alertToReRule = new events.Rule(this, 'InventoryAlertToRe', {
      eventBus: this.eventBus,
      ruleName: `smartretail-alert-to-re-${srEnv}`,
      eventPattern: { source: ['smartretail.ims'], detailType: ['InventoryAlertEvent'] },
      targets: [new eventsTargets.SqsQueue(this.reAlertQueue, {
        messageGroupId: events.EventField.fromPath('$.detail.dcId'),
      })],
    });

    const put = (name: string, value: string) =>
      new ssm.StringParameter(this, name.replace(/[/-]/g, ''), {
        parameterName: `/smartretail/${srEnv}/${name}`,
        stringValue: value,
      });

    put('eventbridge/bus-name',       this.eventBus.eventBusName);
    put('eventbridge/bus-arn',        this.eventBus.eventBusArn);
    put('sqs/ims-sales-queue-url',    this.imsSalesQueue.queueUrl);
    put('sqs/re-alert-queue-url',     this.reAlertQueue.queueUrl);
    put('sqs/ims-po-queue-url',       this.imsPoQueue.queueUrl);
    put('sqs/ims-forecast-queue-url', this.imsForecQueue.queueUrl);
  }
}
