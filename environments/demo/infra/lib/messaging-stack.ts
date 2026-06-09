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
 * SIS publishes SalesTransactionProcessed to EventBridge; IMS and RE consume
 * via the queues below. ARS receives all domain events for dashboard aggregation.
 */
export class MessagingStack extends cdk.Stack {
  public readonly eventBus: events.EventBus;
  public readonly imsSalesQueue: sqs.Queue;
  public readonly imsSalesDlq: sqs.Queue;
  public readonly reAlertQueue: sqs.Queue;
  public readonly reAlertDlq: sqs.Queue;
  public readonly arsUpdatesQueue: sqs.Queue;
  public readonly arsUpdatesDlq: sqs.Queue;
  public readonly salesToImsRule: events.Rule;
  public readonly alertToReRule: events.Rule;
  public readonly allToArsRule: events.Rule;

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

    this.arsUpdatesDlq = new sqs.Queue(this, 'ArsUpdatesDlq', {
      queueName: `smartretail-ars-updates-${srEnv}-dlq`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      retentionPeriod: cdk.Duration.days(14),
    });
    this.arsUpdatesQueue = new sqs.Queue(this, 'ArsUpdatesQueue', {
      queueName: `smartretail-ars-updates-${srEnv}`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: this.arsUpdatesDlq, maxReceiveCount: 3 },
    });

    // SIS raises SalesTransactionProcessed → IMS picks up for stock decrement
    this.salesToImsRule = new events.Rule(this, 'SalesToIms', {
      eventBus: this.eventBus,
      ruleName: `smartretail-sales-to-ims-${srEnv}`,
      eventPattern: { source: ['smartretail.sis'], detailType: ['SalesTransactionProcessed'] },
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

    // All domain events → ARS for dashboard aggregation
    this.allToArsRule = new events.Rule(this, 'AllEventsToArs', {
      eventBus: this.eventBus,
      ruleName: `smartretail-all-to-ars-${srEnv}`,
      eventPattern: { source: ['smartretail.ims', 'smartretail.re'] },
      targets: [new eventsTargets.SqsQueue(this.arsUpdatesQueue)],
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
    put('sqs/ars-updates-queue-url',  this.arsUpdatesQueue.queueUrl);
  }
}
