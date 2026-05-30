import * as cdk from 'aws-cdk-lib';
import * as events from 'aws-cdk-lib/aws-events';
import * as eventsTargets from 'aws-cdk-lib/aws-events-targets';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface MessagingStackProps extends cdk.StackProps {
  srEnv: string;
}

export class MessagingStack extends cdk.Stack {
  public readonly eventBus: events.EventBus;
  public readonly imsSalesQueue: sqs.Queue;
  public readonly reAlertQueue: sqs.Queue;
  public readonly arsUpdatesQueue: sqs.Queue;

  constructor(scope: Construct, id: string, props: MessagingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-messaging');

    const { srEnv } = props;

    this.eventBus = new events.EventBus(this, 'SmartRetailBus', {
      eventBusName: `smartretail-events-${srEnv}`,
    });

    const imsSalesDlq = new sqs.Queue(this, 'ImsSalesDlq', {
      queueName: `smartretail-ims-sales-${srEnv}-dlq`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      retentionPeriod: cdk.Duration.days(14),
    });
    this.imsSalesQueue = new sqs.Queue(this, 'ImsSalesQueue', {
      queueName: `smartretail-ims-sales-${srEnv}`,
      deadLetterQueue: { queue: imsSalesDlq, maxReceiveCount: 3 },
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    const reAlertDlq = new sqs.Queue(this, 'ReAlertDlq', {
      queueName: `smartretail-re-alert-${srEnv}-dlq.fifo`,
      fifo: true,
    });
    this.reAlertQueue = new sqs.Queue(this, 'ReAlertQueue', {
      queueName: `smartretail-re-alert-${srEnv}.fifo`,
      fifo: true,
      contentBasedDeduplication: true,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: reAlertDlq, maxReceiveCount: 3 },
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    const arsUpdatesDlq = new sqs.Queue(this, 'ArsUpdatesDlq', {
      queueName: `smartretail-ars-updates-${srEnv}-dlq`,
      retentionPeriod: cdk.Duration.days(14),
    });
    this.arsUpdatesQueue = new sqs.Queue(this, 'ArsUpdatesQueue', {
      queueName: `smartretail-ars-updates-${srEnv}`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: arsUpdatesDlq, maxReceiveCount: 3 },
    });

    new events.Rule(this, 'SalesTransactionToIms', {
      eventBus: this.eventBus,
      ruleName: `smartretail-sales-to-ims-${srEnv}`,
      eventPattern: { source: ['smartretail.sis'], detailType: ['SalesTransactionEvent'] },
      targets: [new eventsTargets.SqsQueue(this.imsSalesQueue)],
    });

    new events.Rule(this, 'InventoryAlertToRe', {
      eventBus: this.eventBus,
      ruleName: `smartretail-alert-to-re-${srEnv}`,
      eventPattern: { source: ['smartretail.ims'], detailType: ['InventoryAlertEvent'] },
      targets: [new eventsTargets.SqsQueue(this.reAlertQueue, {
        messageGroupId: events.EventField.fromPath('$.detail.dcId'),
      })],
    });

    new events.Rule(this, 'AllEventsToArs', {
      eventBus: this.eventBus,
      ruleName: `smartretail-all-to-ars-${srEnv}`,
      eventPattern: { source: ['smartretail.sis', 'smartretail.ims', 'smartretail.re'] },
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
