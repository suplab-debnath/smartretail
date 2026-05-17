#!/usr/bin/env python3
"""
publish-pos-event.py — Publish test POS events to Kinesis or directly to SIS REST API.

Usage:
  # Publish to Kinesis (default — triggers full Flow 1)
  python3 scripts/publish-pos-event.py \
    --transaction-id <uuid> \
    --sku-id SKU-BEV-001 \
    --dc-id DC-LONDON \
    --store-id STORE-001 \
    --quantity 30 \
    --unit-price 8.50 \
    --channel POS

  # Publish directly to SIS REST API (bypasses Kinesis — useful for isolated testing)
  python3 scripts/publish-pos-event.py \
    --transaction-id <uuid> \
    --sku-id SKU-BEV-001 \
    --dc-id DC-LONDON \
    --store-id STORE-001 \
    --quantity 30 \
    --unit-price 8.50 \
    --channel POS \
    --direct-api https://{api-id}.execute-api.us-east-1.amazonaws.com/internal \
    --return-status

  # Publish Flow 2 alert directly to RE SQS FIFO queue
  python3 scripts/publish-pos-event.py --flow2-direct --sku-id SKU-BEV-003 --dc-id DC-LONDON
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import uuid
from datetime import datetime, timezone

import boto3

def parse_args():
  parser = argparse.ArgumentParser(description='Publish test POS events')
  parser.add_argument('--transaction-id', default=str(uuid.uuid4()))
  parser.add_argument('--sku-id', default='SKU-BEV-001')
  parser.add_argument('--dc-id', default='DC-LONDON')
  parser.add_argument('--store-id', default='STORE-001')
  parser.add_argument('--quantity', type=int, default=30)
  parser.add_argument('--unit-price', type=float, default=8.50)
  parser.add_argument('--channel', default='POS', choices=['POS', 'ECOMMERCE'])
  parser.add_argument('--region', default='us-east-1')
  parser.add_argument('--env', default=os.environ.get('SMARTRETAIL_ENV', 'dev'))
  parser.add_argument('--direct-api', default=None,
            help='If set, publish directly to SIS REST API instead of Kinesis')
  parser.add_argument('--return-status', action='store_true',
            help='Print HTTP status code and exit (for smoke test use)')
  parser.add_argument('--flow2-direct', action='store_true',
            help='Inject InventoryAlertEvent directly into RE SQS FIFO queue')
  return parser.parse_args()

def build_pos_event(args):
  return {
    "transactionId": args.transaction_id,
    "storeId": args.store_id,
    "skuId": args.sku_id,
    "dcId": args.dc_id,
    "quantity": args.quantity,
    "unitPrice": args.unit_price,
    "channel": args.channel,
    "eventTimestamp": datetime.now(timezone.utc).isoformat()
  }

def publish_to_kinesis(event, args):
  """Publish POS event to Kinesis Data Stream."""
  ssm = boto3.client('ssm', region_name=args.region)
  stream_name = ssm.get_parameter(
    Name=f'/smartretail/{args.env}/kinesis/stream-name'
  )['Parameter']['Value']

  kinesis = boto3.client('kinesis', region_name=args.region)
  response = kinesis.put_record(
    StreamName=stream_name,
    Data=json.dumps(event).encode('utf-8'),
    PartitionKey=f"{event['dcId']}#{event['skuId']}"
  )

  print(f"✅Published to Kinesis stream: {stream_name}")
  print(f"  Shard ID: {response['ShardId']}")
  print(f"  Sequence: {response['SequenceNumber']}")
  print(f"  Transaction ID: {event['transactionId']}")
  print(f"  SHA-256 (idempotency key): {hashlib.sha256(event['transactionId'].encode()).hexdigest()}")

def publish_to_api(event, api_base_url, return_status):
  """Publish directly to SIS REST API (bypasses Kinesis Consumer Lambda)."""
  import urllib.request
  import urllib.error

  url = f"{api_base_url}/v1/ingest/events"
  data = json.dumps(event).encode('utf-8')
  req = urllib.request.Request(
    url,
    data=data,
    headers={'Content-Type': 'application/json'},
    method='POST'
  )

  try:
    with urllib.request.urlopen(req) as response:
      status = response.status
      body = json.loads(response.read().decode())
      if return_status:
        print(str(status))
      else:
        print(f"✅SIS accepted event: {status}")
        print(f"  Response: {body}")
  except urllib.error.HTTPError as e:
    status = e.code
    if return_status:
      print(str(status))
    else:
      body = json.loads(e.read().decode())
      print(f"{'⚠️' if status == 409 else '❌'} SIS returned {status}: {body}")

def inject_flow2_alert(args):
  """
  Inject an InventoryAlertEvent directly into the RE SQS FIFO queue.
  Useful for testing Flow 2 independently of Flow 1.
  """
  is_local = args.env == 'local'
  localstack_endpoint = 'http://localhost:4566'
  boto_kwargs = {'region_name': args.region}
  if is_local:
    boto_kwargs['endpoint_url'] = localstack_endpoint
    boto_kwargs['aws_access_key_id'] = 'test'
    boto_kwargs['aws_secret_access_key'] = 'test'

  ssm = boto3.client('ssm', **boto_kwargs)
  queue_url = ssm.get_parameter(
    Name=f'/smartretail/{args.env}/sqs/re-alert-queue-url'
  )['Parameter']['Value']

  alert_event = {
    "source": "smartretail.ims",
    "detail-type": "InventoryAlertEvent",
    "detail": {
      "alertId": str(uuid.uuid4()),
      "positionId": str(uuid.uuid4()),
      "skuId": args.sku_id,
      "dcId": args.dc_id,
      "alertType": "LOW_STOCK",
      "severity": "HIGH",
      "atp": 45,
      "reorderPoint": 100,
      "currentOnHand": 45
    }
  }

  sqs = boto3.client('sqs', **boto_kwargs)
  message_group_id = f"{args.dc_id}#{args.sku_id}"
  message_dedup_id = hashlib.sha256(
    f"{alert_event['detail']['alertId']}".encode()
  ).hexdigest()

  sqs.send_message(
    QueueUrl=queue_url,
    MessageBody=json.dumps(alert_event),
    MessageGroupId=message_group_id,
    MessageDeduplicationId=message_dedup_id
  )

  print(f"✅Injected InventoryAlertEvent into RE FIFO queue")
  print(f"  SKU: {args.sku_id}, DC: {args.dc_id}")
  print(f"  Message group: {message_group_id}")

def main():
  args = parse_args()

  if args.flow2_direct:
    inject_flow2_alert(args)
    return

  event = build_pos_event(args)

  if args.direct_api:
    publish_to_api(event, args.direct_api, args.return_status)
  else:
    publish_to_kinesis(event, args)

if __name__ == '__main__':
  main()



 
