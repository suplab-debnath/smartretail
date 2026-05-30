import * as cdk from 'aws-cdk-lib';
import * as events from 'aws-cdk-lib/aws-events';
import * as eventsTargets from 'aws-cdk-lib/aws-events-targets';
import * as firehose from 'aws-cdk-lib/aws-kinesisfirehose';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';

export interface MessagingStackProps extends cdk.StackProps {
  srEnv: string;
  posArchiveBucket: s3.Bucket;
}

export class MessagingStack extends cdk.Stack {
  public readonly firehoseStream: firehose.CfnDeliveryStream;
  public readonly eventBus: events.EventBus;
  public readonly imsSalesQueue: sqs.Queue;
  public readonly imsSalesDlq: sqs.Queue;
  public readonly reAlertQueue: sqs.Queue;
  public readonly reAlertDlq: sqs.Queue;
  public readonly arsUpdatesQueue: sqs.Queue;
  public readonly arsUpdatesDlq: sqs.Queue;

  constructor(scope: Construct, id: string, props: MessagingStackProps) {
    super(scope, id, props);

    cdk.Tags.of(this).add('Name', 'smartretail-messaging');

    const { srEnv, posArchiveBucket } = props;

    // IAM role for Firehose to write to S3
    const firehoseRole = new iam.Role(this, 'FirehoseRole', {
      roleName: `smartretail-firehose-role-${srEnv}`,
      assumedBy: new iam.ServicePrincipal('firehose.amazonaws.com'),
    });
    firehoseRole.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:PutObject', 's3:PutObjectAcl', 's3:GetBucketLocation', 's3:ListBucket'],
      resources: [posArchiveBucket.bucketArn, posArchiveBucket.arnForObjects('*')],
    }));

    // Kinesis Data Firehose — Direct PUT source, S3 destination
    // SIS calls firehose.putRecord as an outbound archival adapter.
    // No Lambda trigger on this stream — archival only.
    this.firehoseStream = new firehose.CfnDeliveryStream(this, 'PosArchiveFirehose', {
      deliveryStreamName: `smartretail-pos-archive-${srEnv}`,
      deliveryStreamType: 'DirectPut',
      s3DestinationConfiguration: {
        bucketArn: posArchiveBucket.bucketArn,
        roleArn: firehoseRole.roleArn,
        prefix: 'pos-events/!{timestamp:yyyy}/!{timestamp:MM}/!{timestamp:dd}/!{timestamp:HH}/',
        errorOutputPrefix: 'pos-events-errors/!{firehose:error-output-type}/!{timestamp:yyyy}/!{timestamp:MM}/!{timestamp:dd}/',
        bufferingHints: { intervalInSeconds: 60, sizeInMBs: 5 },
        compressionFormat: 'UNCOMPRESSED',
      },
    });
    this.firehoseStream.node.addDependency(firehoseRole);

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
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: this.imsSalesDlq, maxReceiveCount: 3 },
      visibilityTimeout: cdk.Duration.seconds(120),
    });

    this.reAlertDlq = new sqs.Queue(this, 'ReAlertDlq', {
      queueName: `smartretail-re-alert-${srEnv}-dlq.fifo`,
      fifo: true,
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
      retentionPeriod: cdk.Duration.days(14),
    });
    this.arsUpdatesQueue = new sqs.Queue(this, 'ArsUpdatesQueue', {
      queueName: `smartretail-ars-updates-${srEnv}`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      deadLetterQueue: { queue: this.arsUpdatesDlq, maxReceiveCount: 3 },
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

    put('firehose/delivery-stream-name', this.firehoseStream.ref);
    put('eventbridge/bus-name',          this.eventBus.eventBusName);
    put('eventbridge/bus-arn',           this.eventBus.eventBusArn);
    put('sqs/ims-sales-queue-url',       this.imsSalesQueue.queueUrl);
    put('sqs/re-alert-queue-url',        this.reAlertQueue.queueUrl);
    put('sqs/ars-updates-queue-url',     this.arsUpdatesQueue.queueUrl);
  }
}
